package com.nebdev.devopsagent.saas.organization;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.identity.AuthService;
import com.nebdev.devopsagent.saas.identity.IdentityRepositories;
import com.nebdev.devopsagent.saas.identity.Locales;
import com.nebdev.devopsagent.saas.identity.OneTimeToken;
import com.nebdev.devopsagent.saas.identity.Passwords;
import com.nebdev.devopsagent.saas.identity.UserAccount;
import com.nebdev.devopsagent.saas.notification.NotificationService;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OrganizationService {

    static final Duration INVITATION_TTL = Duration.ofDays(7);

    public record Registered(AuthService.Session session, Organization organization) {}

    public record MemberView(UUID membershipId, UUID userId, String email, String displayName, String roleCode,
                             String status) {}

    private final OrganizationRepositories.Organizations orgs;
    private final OrganizationRepositories.Memberships memberships;
    private final OrganizationRepositories.Roles roles;
    private final IdentityRepositories.Users users;
    private final AuthService auth;
    private final Passwords passwords;
    private final OrgAccess access;
    private final OrganizationPorts.Lifecycle lifecycle;
    private final OrganizationPorts.MemberQuotaPolicy memberQuota;
    private final NotificationService notifications;
    private final AuditService audit;
    private final RlsContext rls;
    private final SaasProperties properties;

    public OrganizationService(OrganizationRepositories.Organizations orgs,
                               OrganizationRepositories.Memberships memberships, OrganizationRepositories.Roles roles,
                               IdentityRepositories.Users users, AuthService auth, Passwords passwords, OrgAccess access,
                               OrganizationPorts.Lifecycle lifecycle, OrganizationPorts.MemberQuotaPolicy memberQuota,
                               NotificationService notifications, AuditService audit, RlsContext rls,
                               SaasProperties properties) {
        this.orgs = orgs;
        this.memberships = memberships;
        this.roles = roles;
        this.users = users;
        this.auth = auth;
        this.passwords = passwords;
        this.access = access;
        this.lifecycle = lifecycle;
        this.memberQuota = memberQuota;
        this.notifications = notifications;
        this.audit = audit;
        this.rls = rls;
        this.properties = properties;
    }

    /** Inscription self-service : compte + organisation (propriétaire) + période d'essai + session. */
    @Transactional
    public Registered register(String email, String password, String displayName, String organizationName,
                               String locale, String timeZone, String currency, String ip, String userAgent) {
        UserAccount user = auth.createUser(email, password, displayName, locale, timeZone);
        Organization org = createFor(user, organizationName, locale, timeZone, currency);
        return new Registered(auth.openSession(user, ip, userAgent), org);
    }

    @Transactional
    public Organization create(String name, String locale, String timeZone, String currency) {
        Actor actor = Actor.require();
        if (actor.type() != Actor.Type.USER) {
            throw ApiException.forbidden("user_required");
        }
        UserAccount user = users.findById(actor.userId()).orElseThrow(() -> ApiException.unauthorized("auth_required"));
        return createFor(user, name, locale == null ? user.getLocale() : locale, timeZone, currency);
    }

    /** Migration (ADR-057) : organisation créée pour un propriétaire repris, sans session ni contexte HTTP. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Organization createForImport(UserAccount owner, String name, String locale, String timeZone, String currency) {
        return createFor(owner, name, locale, timeZone, currency);
    }

    /** Migration (ADR-057) : membre repris avec un rôle système, dans le contexte RLS de l'organisation. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void addMemberForImport(UUID orgId, UUID userId, String roleCode) {
        rls.enterOrg(orgId);
        if (memberships.findByOrganizationIdAndUserId(orgId, userId).isEmpty()) {
            memberships.save(new Membership(orgId, userId, roleCode));
            audit.record(orgId, null, AuditService.Entry.success("member.imported", "user", userId,
                    Map.of("role", roleCode, "source", "platform")));
        }
    }

    private Organization createFor(UserAccount owner, String name, String locale, String timeZone, String currency) {
        if (name == null || name.isBlank() || name.length() > 120) {
            throw ApiException.badRequest("invalid_organization_name");
        }
        Organization org;
        try {
            org = new Organization(name, uniqueSlug(name), Locales.normalize(locale), timeZone, currency);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_currency");
        }
        orgs.save(org);
        memberships.save(new Membership(org.getId(), owner.getId(), OrgPermissions.OWNER));
        rls.enterOrg(org.getId());
        lifecycle.onOrganizationCreated(org.getId(), org.getBillingCurrency());
        audit.record(org.getId(), auth.actorOf(owner), AuditService.Entry.success("organization.created", "organization",
                org.getId(), Map.of("name", org.getName(), "slug", org.getSlug())));
        return org;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine() {
        Actor actor = Actor.require();
        if (actor.type() == Actor.Type.API_TOKEN) {
            return orgs.findById(actor.tokenOrgId()).map(o -> List.of(view(o, "api_token", actor.scopes()))).orElse(List.of());
        }
        return orgs.forUser(actor.userId()).stream().map(o -> {
            String role = memberships.findByOrganizationIdAndUserId(o.getId(), actor.userId())
                    .map(Membership::getRoleCode).orElse(null);
            return view(o, role, access.permissionsOf(o.getId(), role));
        }).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID orgId) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.ORG_READ);
        return view(ctx.organization(), ctx.via(), ctx.permissions());
    }

    @Transactional
    public Map<String, Object> update(UUID orgId, String name, String locale, String timeZone, String currency,
                                      Boolean fourEyes) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.ORG_MANAGE);
        if (timeZone != null) {
            try {
                java.time.ZoneId.of(timeZone);
            } catch (Exception e) {
                throw ApiException.badRequest("invalid_time_zone");
            }
        }
        try {
            ctx.organization().update(name, locale == null ? null : Locales.normalize(locale), timeZone, currency, fourEyes);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_currency");
        }
        audit.record(orgId, AuditService.Entry.success("organization.updated", "organization", orgId,
                Map.of("fourEyes", ctx.organization().isRequireFourEyes())));
        return view(ctx.organization(), ctx.via(), ctx.permissions());
    }

    // ───────────── Membres ─────────────

    @Transactional(readOnly = true)
    public List<MemberView> members(UUID orgId) {
        access.require(orgId, OrgPermissions.MEMBER_READ);
        return memberships.findByOrganizationIdOrderByCreatedAt(orgId).stream().map(m -> {
            UserAccount u = users.findById(m.getUserId()).orElse(null);
            return new MemberView(m.getId(), m.getUserId(), u == null ? "" : u.getEmail(),
                    u == null ? "" : u.getDisplayName(), m.getRoleCode(), m.getStatus());
        }).toList();
    }

    /** Invitation par e-mail (le compte est créé ou rattaché à l'acceptation). */
    @Transactional
    public Map<String, Object> invite(UUID orgId, String email, String roleCode) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.MEMBER_MANAGE);
        String e = AuthService.normalizeEmail(email);
        requireAssignableRole(ctx, roleCode);
        users.findByEmail(e).flatMap(u -> memberships.findByOrganizationIdAndUserId(orgId, u.getId())).ifPresent(m -> {
            throw ApiException.conflict("already_member");
        });
        memberQuota.assertCanAddMember(orgId, memberships.countByOrganizationIdAndStatus(orgId, "ACTIVE"));
        String token = auth.issueToken(OneTimeToken.Purpose.INVITATION, null, e, orgId, roleCode, INVITATION_TTL);
        String locale = users.findByEmail(e).map(UserAccount::getLocale).orElse(ctx.organization().getDefaultLocale());
        notifications.email(orgId, null, e, "invitation", locale, ctx.organization().getName(),
                properties.getPublicUrl().replaceAll("/$", "") + "/accept-invitation?token=" + token, INVITATION_TTL.toDays());
        audit.record(orgId, AuditService.Entry.success("member.invited", "membership", null,
                Map.of("email", e, "role", roleCode)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", e);
        out.put("role", roleCode);
        out.put("expiresInDays", INVITATION_TTL.toDays());
        return out;
    }

    /**
     * Acceptation : compte existant ⇒ son mot de passe prouve l'identité ; sinon le compte est créé.
     * Public (le jeton fait foi), d'où une session ouverte en retour.
     */
    @Transactional
    public Registered acceptInvitation(String rawToken, String password, String displayName, String ip, String userAgent) {
        OneTimeToken t = auth.consume(rawToken, OneTimeToken.Purpose.INVITATION);
        UserAccount user = users.findByEmail(t.getEmail()).orElse(null);
        if (user == null) {
            user = auth.createUser(t.getEmail(), password, displayName, null, null, false);
        } else if (!passwords.matches(password, user.getPasswordHash())) {
            throw ApiException.unauthorized("invalid_credentials");
        }
        Organization org = orgs.findById(t.getOrganizationId()).orElseThrow(() -> ApiException.notFound("organization"));
        UUID userId = user.getId();
        if (memberships.findByOrganizationIdAndUserId(org.getId(), userId).isEmpty()) {
            rls.enterOrg(org.getId());
            memberQuota.assertCanAddMember(org.getId(), memberships.countByOrganizationIdAndStatus(org.getId(), "ACTIVE"));
            memberships.save(new Membership(org.getId(), userId, t.getRoleCode()));
            audit.record(org.getId(), auth.actorOf(user), AuditService.Entry.success("member.joined", "membership", userId,
                    Map.of("role", t.getRoleCode())));
        }
        return new Registered(auth.openSession(user, ip, userAgent), org);
    }

    @Transactional
    public MemberView changeRole(UUID orgId, UUID userId, String roleCode) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.MEMBER_MANAGE);
        requireAssignableRole(ctx, roleCode);
        Membership m = memberships.findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> ApiException.notFound("member"));
        if (OrgPermissions.OWNER.equals(m.getRoleCode()) && !OrgPermissions.OWNER.equals(roleCode)) {
            requireAnotherOwner(orgId);
        }
        String previous = m.getRoleCode();
        m.setRoleCode(roleCode);
        audit.record(orgId, AuditService.Entry.success("member.role_changed", "membership", userId,
                Map.of("from", previous, "to", roleCode)));
        UserAccount u = users.findById(userId).orElseThrow();
        return new MemberView(m.getId(), userId, u.getEmail(), u.getDisplayName(), roleCode, m.getStatus());
    }

    @Transactional
    public void remove(UUID orgId, UUID userId) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.MEMBER_MANAGE);
        Membership m = memberships.findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> ApiException.notFound("member"));
        if (OrgPermissions.OWNER.equals(m.getRoleCode())) {
            if (!ctx.can(OrgPermissions.ORG_MANAGE) || !OrgPermissions.OWNER.equals(roleOf(orgId, ctx.userId()))) {
                throw ApiException.forbidden("owner_only");
            }
            requireAnotherOwner(orgId);
        }
        memberships.delete(m);
        audit.record(orgId, AuditService.Entry.success("member.removed", "membership", userId, Map.of()));
    }

    @Transactional(readOnly = true)
    public List<OrgRole> roles(UUID orgId) {
        access.require(orgId, OrgPermissions.MEMBER_READ);
        return roles.available(orgId);
    }

    @Transactional
    public OrgRole createRole(UUID orgId, String code, String label, Set<String> permissions) {
        access.require(orgId, OrgPermissions.ORG_MANAGE);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,40}")) {
            throw ApiException.badRequest("invalid_role_code");
        }
        if (!roles.resolve(orgId, code).isEmpty()) {
            throw ApiException.conflict("role_exists");
        }
        try {
            OrgRole role = roles.save(new OrgRole(orgId, code, label == null ? code : label.strip(),
                    new LinkedHashSet<>(permissions == null ? Set.of() : permissions)));
            audit.record(orgId, AuditService.Entry.success("role.created", "role", role.getId(),
                    Map.of("code", code, "permissions", List.copyOf(role.getPermissions()))));
            return role;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_permission", e.getMessage());
        }
    }

    private void requireAssignableRole(OrgAccess.OrgContext ctx, String roleCode) {
        if (roleCode == null || roles.resolve(ctx.orgId(), roleCode).isEmpty()) {
            throw ApiException.badRequest("unknown_role");
        }
        // Personne ne donne plus de droits qu'il n'en a ; seul un propriétaire nomme un propriétaire.
        if (OrgPermissions.OWNER.equals(roleCode) && !OrgPermissions.OWNER.equals(roleOf(ctx.orgId(), ctx.userId()))) {
            throw ApiException.forbidden("owner_only");
        }
        if (!ctx.permissions().containsAll(access.permissionsOf(ctx.orgId(), roleCode))) {
            throw ApiException.forbidden("role_exceeds_actor");
        }
    }

    private String roleOf(UUID orgId, UUID userId) {
        return userId == null ? null : memberships.findByOrganizationIdAndUserId(orgId, userId)
                .map(Membership::getRoleCode).orElse(null);
    }

    private void requireAnotherOwner(UUID orgId) {
        if (memberships.countByOrganizationIdAndRoleCodeAndStatus(orgId, OrgPermissions.OWNER, "ACTIVE") <= 1) {
            throw ApiException.conflict("last_owner");
        }
    }

    private String uniqueSlug(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "org";
        }
        base = base.substring(0, Math.min(40, base.length()));
        String slug = base;
        int i = 2;
        while (orgs.existsBySlug(slug)) {
            slug = base + "-" + i++;
        }
        return slug;
    }

    public static Map<String, Object> view(Organization o, String role, Set<String> permissions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("name", o.getName());
        m.put("slug", o.getSlug());
        m.put("customerType", o.getCustomerType());
        m.put("defaultLocale", o.getDefaultLocale());
        m.put("timeZone", o.getTimeZone());
        m.put("billingCurrency", o.getBillingCurrency());
        m.put("requireFourEyes", o.isRequireFourEyes());
        m.put("status", o.getStatus());
        m.put("role", role);
        m.put("permissions", permissions == null ? List.of() : List.copyOf(new java.util.TreeSet<>(permissions)));
        m.put("createdAt", o.getCreatedAt());
        return m;
    }
}
