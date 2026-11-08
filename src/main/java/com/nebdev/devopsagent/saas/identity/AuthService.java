package com.nebdev.devopsagent.saas.identity;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.shared.CryptoService;
import com.nebdev.devopsagent.saas.notification.NotificationService;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.Hashing;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authentification du SaaS : mot de passe (BCrypt), MFA TOTP facultatif, sessions opaques hachées en base,
 * réinitialisation par jeton à usage unique, verrouillage après N échecs. N'a aucune connaissance des
 * organisations (ADR-047 : identity ne dépend de personne).
 */
@Service
public class AuthService {

    public static final String SESSION_PREFIX = "ses";
    static final Duration RESET_TTL = Duration.ofMinutes(30);
    static final Duration VERIFY_TTL = Duration.ofDays(3);

    public record Session(String token, Instant expiresAt, UserAccount user) {}

    public record MfaSetup(String secretBase32, String otpauthUri) {}

    private final IdentityRepositories.Users users;
    private final IdentityRepositories.Staff staff;
    private final IdentityRepositories.Sessions sessions;
    private final IdentityRepositories.Tokens tokens;
    private final Passwords passwords;
    private final LoginThrottle throttle;
    private final CryptoService crypto;
    private final NotificationService notifications;
    private final AuditService audit;
    private final RlsContext rls;
    private final SaasProperties properties;

    public AuthService(IdentityRepositories.Users users, IdentityRepositories.Staff staff,
                       IdentityRepositories.Sessions sessions, IdentityRepositories.Tokens tokens, Passwords passwords,
                       LoginThrottle throttle, CryptoService crypto, NotificationService notifications,
                       AuditService audit, RlsContext rls, SaasProperties properties) {
        this.users = users;
        this.staff = staff;
        this.sessions = sessions;
        this.tokens = tokens;
        this.passwords = passwords;
        this.throttle = throttle;
        this.crypto = crypto;
        this.notifications = notifications;
        this.audit = audit;
        this.rls = rls;
        this.properties = properties;
    }

    /** Création d'un compte utilisateur (appelée par l'inscription ou l'acceptation d'invitation). */
    @Transactional
    public UserAccount createUser(String email, String rawPassword, String displayName, String locale, String timeZone) {
        return createUser(email, rawPassword, displayName, locale, timeZone, true);
    }

    /**
     * Migration depuis le profil {@code platform} (ADR-057) : compte repris avec son hachage BCrypt d'origine (le mot
     * de passe ne change pas, il n'est jamais connu). Adresse considérée vérifiée (elle l'était sur la plateforme).
     * Jamais exposé par l'API.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UserAccount importUser(String email, String bcryptHash, String displayName, String locale, boolean active) {
        String e = normalizeEmail(email);
        if (bcryptHash == null || !bcryptHash.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$")) {
            throw new IllegalArgumentException("Hachage BCrypt invalide pour " + e);
        }
        UserAccount user = users.save(new UserAccount(e, bcryptHash,
                displayName == null || displayName.isBlank() ? e : displayName.strip(), Locales.normalize(locale), "UTC"));
        user.verifyEmail(Instant.now());
        if (!active) {
            user.setStatus("DISABLED");
        }
        return user;
    }

    /** @param verify false quand l'adresse est déjà prouvée (invitation reçue par e-mail). */
    @Transactional
    public UserAccount createUser(String email, String rawPassword, String displayName, String locale, String timeZone,
                                  boolean verify) {
        String e = normalizeEmail(email);
        if (users.findByEmail(e).isPresent()) {
            throw ApiException.conflict("email_taken");
        }
        UserAccount user = users.save(new UserAccount(e, passwords.hash(rawPassword, e), displayName, locale, timeZone));
        if (verify) {
            sendVerification(user);
        } else {
            user.verifyEmail(Instant.now());
        }
        return user;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session login(String email, String rawPassword, String totpCode, String ip, String userAgent) {
        String e = email == null ? "" : email.strip().toLowerCase();
        throttle.check("login", ip, e);
        rls.enterPlatform();
        Instant now = Instant.now();
        Optional<UserAccount> found = users.findByEmail(e);
        if (found.isEmpty()) {
            passwords.burn(rawPassword);
            audit.record(null, null, AuditService.Entry.failure("auth.login", "user", null, Map.of("reason", "unknown_email")));
            throw ApiException.unauthorized("invalid_credentials");
        }
        UserAccount user = found.get();
        if (user.isLocked(now)) {
            passwords.burn(rawPassword);
            throw new ApiException(HttpStatus.LOCKED, "account_locked", properties.getAuth().getLockoutMinutes());
        }
        if (!user.isActive() || !passwords.matches(rawPassword, user.getPasswordHash())) {
            user.recordFailedLogin(properties.getAuth().getMaxFailedLogins(),
                    now.plus(Duration.ofMinutes(properties.getAuth().getLockoutMinutes())));
            audit.record(null, null, AuditService.Entry.failure("auth.login", "user", user.getId(), Map.of("reason", "bad_password")));
            throw ApiException.unauthorized("invalid_credentials");
        }
        if (user.isMfaEnabled()) {
            if (totpCode == null || totpCode.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "mfa_required");
            }
            byte[] secret = crypto.decryptPlatform("mfa|" + user.getId(), user.getMfaSecretEnc());
            if (!Totp.verify(secret, totpCode.strip(), now)) {
                user.recordFailedLogin(properties.getAuth().getMaxFailedLogins(),
                        now.plus(Duration.ofMinutes(properties.getAuth().getLockoutMinutes())));
                throw ApiException.unauthorized("mfa_invalid");
            }
        }
        user.recordSuccessfulLogin(now);
        Session s = openSession(user, ip, userAgent);
        audit.record(null, actorOf(user), AuditService.Entry.success("auth.login", "user", user.getId(), Map.of()));
        return s;
    }

    @Transactional
    public Session openSession(UserAccount user, String ip, String userAgent) {
        String token = Hashing.randomToken(SESSION_PREFIX);
        Instant expires = Instant.now().plus(Duration.ofHours(properties.getAuth().getSessionHours()));
        sessions.save(new AuthSession(user.getId(), Hashing.sha256Hex(token), expires, ip, userAgent));
        return new Session(token, expires, user);
    }

    /** Résout un jeton de session en acteur (filtre de sécurité). */
    @Transactional
    public Optional<Actor> authenticate(String token) {
        if (token == null || !token.startsWith(SESSION_PREFIX + "_")) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return sessions.findByTokenHash(Hashing.sha256Hex(token))
                .filter(s -> s.isValid(now))
                .flatMap(s -> users.findById(s.getUserId()).filter(UserAccount::isActive).map(u -> {
                    if (s.getLastSeenAt() == null || s.getLastSeenAt().isBefore(now.minusSeconds(60))) {
                        s.seen(now);
                    }
                    return actorOf(u);
                }));
    }

    /**
     * Le rôle de personnel INOVNE n'est effectif qu'avec la MFA active (directive §5) : sans elle, le compte n'a que
     * des droits d'utilisateur ordinaire. Fail-closed : désactiver sa MFA retire aussitôt les pouvoirs du personnel.
     */
    public Actor actorOf(UserAccount u) {
        String staffRole = staff.findById(u.getId()).map(PlatformStaff::getRole)
                .filter(r -> u.isMfaEnabled() || !properties.getAuth().isStaffMfaRequired())
                .orElse(null);
        return new Actor(Actor.Type.USER, u.getId(), u.getEmail(), staffRole, null, null, Set.of(), u.getLocale());
    }

    /** Rôle de personnel en attente d'activation de la MFA (affiché pour inviter à l'activer), sinon vide. */
    public Optional<String> staffRolePendingMfa(UserAccount u) {
        if (u.isMfaEnabled() || !properties.getAuth().isStaffMfaRequired()) {
            return Optional.empty();
        }
        return staff.findById(u.getId()).map(PlatformStaff::getRole);
    }

    @Transactional
    public void logout(String token) {
        if (token == null) {
            return;
        }
        sessions.findByTokenHash(Hashing.sha256Hex(token)).ifPresent(s -> s.revoke(Instant.now()));
    }

    @Transactional
    public void logoutEverywhere(UUID userId) {
        sessions.revokeAll(userId, Instant.now());
    }

    @Transactional
    public void changePassword(UUID userId, String current, String next) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
        if (!passwords.matches(current, user.getPasswordHash())) {
            throw ApiException.forbidden("invalid_current_password");
        }
        if (current.equals(next)) {
            throw ApiException.badRequest("password_unchanged");
        }
        user.changePassword(passwords.hash(next, user.getEmail()));
        rls.enterPlatform();
        audit.record(null, actorOf(user), AuditService.Entry.success("auth.password_changed", "user", userId, Map.of()));
    }

    /** Toujours la même réponse, que l'e-mail existe ou non (pas d'énumération des comptes). */
    @Transactional
    public void requestPasswordReset(String email, String ip) {
        String e = email == null ? "" : email.strip().toLowerCase();
        throttle.check("reset", ip, e);
        users.findByEmail(e).filter(UserAccount::isActive).ifPresent(user -> {
            String raw = Hashing.randomToken("rst");
            tokens.save(new OneTimeToken(OneTimeToken.Purpose.PASSWORD_RESET, user.getId(), user.getEmail(), null, null,
                    Hashing.sha256Hex(raw), Instant.now().plus(RESET_TTL)));
            notifications.email(null, user.getId(), user.getEmail(), "password_reset", user.getLocale(),
                    user.getDisplayName(), link("/reset-password?token=" + raw), RESET_TTL.toMinutes());
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        OneTimeToken t = consume(rawToken, OneTimeToken.Purpose.PASSWORD_RESET);
        UserAccount user = users.findById(t.getUserId()).orElseThrow(() -> ApiException.unauthorized("token_invalid"));
        user.changePassword(passwords.hash(newPassword, user.getEmail()));
        user.recordSuccessfulLogin(user.getLastLoginAt() == null ? Instant.now() : user.getLastLoginAt());
        sessions.revokeAll(user.getId(), Instant.now());
        rls.enterPlatform();
        audit.record(null, actorOf(user), AuditService.Entry.success("auth.password_reset", "user", user.getId(), Map.of()));
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        OneTimeToken t = consume(rawToken, OneTimeToken.Purpose.EMAIL_VERIFY);
        users.findById(t.getUserId()).ifPresent(u -> u.verifyEmail(Instant.now()));
    }

    private void sendVerification(UserAccount user) {
        String raw = Hashing.randomToken("vfy");
        tokens.save(new OneTimeToken(OneTimeToken.Purpose.EMAIL_VERIFY, user.getId(), user.getEmail(), null, null,
                Hashing.sha256Hex(raw), Instant.now().plus(VERIFY_TTL)));
        notifications.email(null, user.getId(), user.getEmail(), "verify_email", user.getLocale(),
                user.getDisplayName(), link("/verify-email?token=" + raw));
    }

    @Transactional
    public OneTimeToken consume(String rawToken, OneTimeToken.Purpose purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("token_invalid");
        }
        OneTimeToken t = tokens.findByTokenHash(Hashing.sha256Hex(rawToken.strip()))
                .filter(x -> x.usableAt(Instant.now(), purpose))
                .orElseThrow(() -> ApiException.unauthorized("token_invalid"));
        t.markUsed(Instant.now());
        return t;
    }

    @Transactional
    public String issueToken(OneTimeToken.Purpose purpose, UUID userId, String email, UUID orgId, String roleCode,
                             Duration ttl) {
        String raw = Hashing.randomToken(purpose == OneTimeToken.Purpose.INVITATION ? "inv" : "tok");
        tokens.save(new OneTimeToken(purpose, userId, email, orgId, roleCode, Hashing.sha256Hex(raw), Instant.now().plus(ttl)));
        return raw;
    }

    @Transactional
    public MfaSetup setupMfa(UUID userId) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
        byte[] secret = Totp.newSecret();
        user.setMfaSecret(crypto.encryptPlatform("mfa|" + user.getId(), secret));
        return new MfaSetup(Totp.base32(secret), Totp.otpauthUri("VPS Autopilot", user.getEmail(), secret));
    }

    @Transactional
    public void enableMfa(UUID userId, String code) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
        if (user.getMfaSecretEnc() == null) {
            throw ApiException.badRequest("mfa_not_setup");
        }
        byte[] secret = crypto.decryptPlatform("mfa|" + user.getId(), user.getMfaSecretEnc());
        if (!Totp.verify(secret, code, Instant.now())) {
            throw ApiException.badRequest("mfa_invalid");
        }
        user.enableMfa();
        rls.enterPlatform();
        audit.record(null, actorOf(user), AuditService.Entry.success("auth.mfa_enabled", "user", userId, Map.of()));
    }

    @Transactional
    public void disableMfa(UUID userId, String password) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
        if (!passwords.matches(password, user.getPasswordHash())) {
            throw ApiException.forbidden("invalid_current_password");
        }
        user.disableMfa();
        rls.enterPlatform();
        audit.record(null, actorOf(user), AuditService.Entry.success("auth.mfa_disabled", "user", userId, Map.of()));
    }

    @Transactional
    public UserAccount updateProfile(UUID userId, String displayName, String locale, String timeZone) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
        if (timeZone != null && !timeZone.isBlank()) {
            try {
                java.time.ZoneId.of(timeZone);
            } catch (Exception e) {
                throw ApiException.badRequest("invalid_time_zone");
            }
        }
        user.setProfile(displayName, locale, timeZone);
        return user;
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void purgeExpiredSessions() {
        sessions.purgeExpired(Instant.now().minus(Duration.ofDays(7)));
    }

    public static String normalizeEmail(String email) {
        if (email == null || !email.strip().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 254) {
            throw ApiException.badRequest("invalid_email");
        }
        return email.strip().toLowerCase();
    }

    private String link(String path) {
        return properties.getPublicUrl().replaceAll("/$", "") + path;
    }
}
