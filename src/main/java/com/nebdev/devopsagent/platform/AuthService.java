package com.nebdev.devopsagent.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccountStore accounts;
    private final MagicTokenStore tokens;
    private final SessionStore sessions;
    private final AccessControl access;
    private final PlatformProperties properties;

    public AuthService(AccountStore accounts, MagicTokenStore tokens, SessionStore sessions,
                       AccessControl access, PlatformProperties properties) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.sessions = sessions;
        this.access = access;
        this.properties = properties;
    }

    public PlatformModels.Session loginWithPassword(String email, String password) throws IOException {
        String e = email == null ? "" : email.strip().toLowerCase();
        Optional<PlatformModels.Account> found = accounts.findByEmail(e);
        if (found.isEmpty() || !found.get().active() || !found.get().hasPassword()
                || !PasswordHashes.matches(password, found.get().passwordHash())) {
            throw PlatformAccessException.unauthorized("E-mail ou mot de passe incorrect.");
        }
        Instant expires = Instant.now().plus(properties.getSessionHours(), ChronoUnit.HOURS);
        log.info("Connexion mot de passe : {}", found.get().email());
        return sessions.create(found.get().id(), expires);
    }

    public void changePassword(String accountId, String currentPassword, String newPassword) throws IOException {
        PlatformModels.Account account = accounts.require(accountId);
        if (!account.hasPassword() || !PasswordHashes.matches(currentPassword, account.passwordHash())) {
            throw PlatformAccessException.forbidden("Mot de passe actuel incorrect.");
        }
        PasswordHashes.requireDifferent(currentPassword, newPassword);
        accounts.setPassword(accountId, newPassword, false);
        log.info("Mot de passe changé pour {}", account.email());
    }

    /**
     * Demande un lien de réinitialisation. Ne révèle pas si l’e-mail existe.
     * En local ({@code mailDevCapture=true}), le lien est renvoyé dans la réponse JSON.
     */
    public Map<String, Object> requestPasswordReset(String email) throws IOException {
        String e = email == null ? "" : email.strip().toLowerCase();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("message", "Si ce compte existe, un lien de réinitialisation a été préparé.");

        Optional<PlatformModels.Account> account = accounts.findByEmail(e);
        if (account.isEmpty() || !account.get().active() || !account.get().hasPassword()) {
            return ok;
        }
        Instant expires = Instant.now().plus(properties.getMagicLinkMinutes(), ChronoUnit.MINUTES);
        PlatformModels.MagicToken token = tokens.create(account.get().id(), account.get().email(), expires);
        String link = properties.getPublicBaseUrl().replaceAll("/$", "")
                + "/reset-password.html?token=" + token.id();
        log.info("Réinitialisation MDP préparée pour {} (expire {})", account.get().email(), expires);
        if (properties.isMailDevCapture()) {
            ok.put("devToken", token.id());
            ok.put("devLink", link);
            ok.put("message", "Lien de réinitialisation prêt (mode local). Ouvre le lien ci-dessous.");
        } else {
            log.warn("mailDevCapture=false mais aucun SMTP portail ; lien reset non transmis pour {}",
                    account.get().email());
        }
        return ok;
    }

    /**
     * Applique un nouveau mot de passe via jeton de reset, puis invalide les sessions du compte.
     */
    public void completePasswordReset(String tokenId, String newPassword) throws IOException {
        PlatformModels.MagicToken token = tokens.consume(tokenId, Instant.now())
                .orElseThrow(() -> PlatformAccessException.unauthorized("Lien invalide ou expiré."));
        PlatformModels.Account account = accounts.require(token.accountId());
        if (!account.active()) {
            throw PlatformAccessException.forbidden("Compte désactivé.");
        }
        PasswordHashes.requirePolicy(newPassword);
        accounts.setPassword(account.id(), newPassword, false);
        sessions.deleteByAccount(account.id());
        log.info("Mot de passe réinitialisé pour {}", account.email());
    }

    /**
     * Remet le mot de passe usine via jeton de reset ({@code mustChangePassword=true}).
     */
    public void completePasswordResetToDefault(String tokenId) throws IOException {
        // S6 : retour au mot de passe usine réservé au mode local (capture des liens), jamais en production.
        String configured = properties.getDefaultPassword();
        if (!properties.isMailDevCapture() || configured == null || configured.isBlank()) {
            throw PlatformAccessException.forbidden(
                    "Retour au mot de passe usine désactivé sur cette instance. Choisis un nouveau mot de passe.");
        }
        PlatformModels.MagicToken token = tokens.consume(tokenId, Instant.now())
                .orElseThrow(() -> PlatformAccessException.unauthorized("Lien invalide ou expiré."));
        PlatformModels.Account account = accounts.require(token.accountId());
        if (!account.active()) {
            throw PlatformAccessException.forbidden("Compte désactivé.");
        }
        String raw = configured;
        accounts.setPassword(account.id(), raw, true);
        sessions.deleteByAccount(account.id());
        log.info("Mot de passe usine rétabli pour {} — à changer à la prochaine connexion.", account.email());
    }

    /**
     * Demande un lien magique. Ne révèle pas si l’e-mail existe (sauf mode capture).
     */
    public Map<String, Object> requestMagicLink(String email) throws IOException {
        String e = email == null ? "" : email.strip().toLowerCase();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("message", "Si ce compte existe, un lien de connexion a été préparé.");

        Optional<PlatformModels.Account> account = accounts.findByEmail(e);
        if (account.isEmpty() || !account.get().active()) {
            return ok;
        }
        Instant expires = Instant.now().plus(properties.getMagicLinkMinutes(), ChronoUnit.MINUTES);
        PlatformModels.MagicToken token = tokens.create(account.get().id(), account.get().email(), expires);
        String link = properties.getPublicBaseUrl().replaceAll("/$", "")
                + "/login.html?token=" + token.id();
        log.info("Lien magique préparé pour {} (expire {})", account.get().email(), expires);
        if (properties.isMailDevCapture()) {
            ok.put("devToken", token.id());
            ok.put("devLink", link);
        } else {
            log.warn("mailDevCapture=false mais aucun envoi SMTP portail configuré ; lien non transmis pour {}",
                    account.get().email());
        }
        return ok;
    }

    public PlatformModels.Session consumeMagicLink(String tokenId) throws IOException {
        PlatformModels.MagicToken token = tokens.consume(tokenId, Instant.now())
                .orElseThrow(() -> PlatformAccessException.unauthorized("Lien invalide ou expiré."));
        PlatformModels.Account account = accounts.require(token.accountId());
        if (!account.active()) {
            throw PlatformAccessException.forbidden("Compte désactivé.");
        }
        Instant expires = Instant.now().plus(properties.getSessionHours(), ChronoUnit.HOURS);
        return sessions.create(account.id(), expires);
    }

    public PlatformPrincipal principalForSession(String sessionId) throws IOException {
        PlatformModels.Session session = sessions.findValid(sessionId, Instant.now())
                .orElseThrow(() -> PlatformAccessException.unauthorized("Session expirée. Reconnectez-vous."));
        PlatformModels.Account account = accounts.require(session.accountId());
        if (!account.active()) {
            sessions.delete(sessionId);
            throw PlatformAccessException.forbidden("Compte désactivé.");
        }
        return access.loadPrincipal(account);
    }

    public void logout(String sessionId) throws IOException {
        if (sessionId != null && !sessionId.isBlank()) {
            sessions.delete(sessionId);
        }
    }

    public Map<String, Object> me(PlatformPrincipal principal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", principal.accountId());
        body.put("email", principal.email());
        body.put("displayName", principal.displayName());
        body.put("roleId", principal.roleId());
        body.put("permissions", principal.permissions());
        body.put("platformAdmin", principal.isPlatformAdmin());
        body.put("mustChangePassword", principal.mustChangePassword());
        return body;
    }
}
