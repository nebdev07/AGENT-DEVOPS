package com.nebdev.devopsagent.saas.organization;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Point de contrôle unique de l'accès aux ressources d'une organisation (ADR-049, ADR-052).
 *
 * <p>Ordre : jeton d'API (organisation + scope) → membre actif (rôle) → personnel INOVNE avec accès support
 * actif. Sinon refus 404 (on ne révèle pas l'existence d'une organisation étrangère) ou 403, tracé dans
 * l'audit de l'organisation visée. En cas de succès, le contexte RLS de l'organisation est posé sur la
 * transaction courante : les requêtes suivantes ne peuvent physiquement lire que cette organisation.
 */
@Service
public class OrgAccess {

    /** Port vers le module managed : accès support INOVNE actif pour (organisation, utilisateur). */
    public interface SupportAccessLookup {
        Optional<String> activeLevel(UUID organizationId, UUID staffUserId, Instant now);
    }

    public record OrgContext(UUID orgId, Actor actor, Set<String> permissions, String via, Organization organization) {
        public boolean can(String permission) {
            return permissions.contains(permission);
        }

        public UUID userId() {
            return actor.userId();
        }
    }

    private final OrganizationRepositories.Organizations orgs;
    private final OrganizationRepositories.Memberships memberships;
    private final OrganizationRepositories.Roles roles;
    private final SupportAccessLookup support;
    private final RlsContext rls;
    private final AuditService audit;

    public OrgAccess(OrganizationRepositories.Organizations orgs, OrganizationRepositories.Memberships memberships,
                     OrganizationRepositories.Roles roles, SupportAccessLookup support, RlsContext rls,
                     AuditService audit) {
        this.orgs = orgs;
        this.memberships = memberships;
        this.roles = roles;
        this.support = support;
        this.rls = rls;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OrgContext require(UUID orgId, String permission) {
        Actor actor = Actor.require();
        if (orgId == null) {
            throw ApiException.notFound("organization");
        }
        Organization org = orgs.findById(orgId).orElse(null);
        if (org == null) {
            throw ApiException.notFound("organization");
        }
        Resolved resolved = resolve(org, actor);
        if (resolved == null) {
            deny(orgId, actor, permission, "not_a_member");
            throw ApiException.notFound("organization");
        }
        if (!org.isActive() && !OrgPermissions.ORG_READ.equals(permission)
                && !OrgPermissions.BILLING_READ.equals(permission) && !OrgPermissions.BILLING_MANAGE.equals(permission)) {
            deny(orgId, actor, permission, "organization_suspended");
            throw new ApiException(HttpStatus.FORBIDDEN, "organization_suspended");
        }
        if (permission != null && !resolved.permissions.contains(permission)) {
            deny(orgId, actor, permission, "missing_permission");
            throw new ApiException(HttpStatus.FORBIDDEN, "missing_permission", Map.of("permission", permission));
        }
        rls.enterOrg(orgId);
        return new OrgContext(orgId, actor, resolved.permissions, resolved.via, org);
    }

    /** Permissions effectives sans contrôle d'une permission précise (affichage de l'interface). */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrgContext enter(UUID orgId) {
        return require(orgId, null);
    }

    private record Resolved(Set<String> permissions, String via) {}

    private Resolved resolve(Organization org, Actor actor) {
        if (actor.type() == Actor.Type.API_TOKEN) {
            if (!org.getId().equals(actor.tokenOrgId())) {
                return null;
            }
            // Le jeton ne survit pas à son créateur : membre actif requis, droits = scopes ∩ rôle actuel.
            Optional<Membership> creator = memberships.findByOrganizationIdAndUserId(org.getId(), actor.userId());
            if (creator.isEmpty() || !creator.get().isActive()) {
                return null;
            }
            Set<String> effective = new java.util.HashSet<>(actor.scopes());
            effective.retainAll(permissionsOf(org.getId(), creator.get().getRoleCode()));
            return new Resolved(Set.copyOf(effective), "api_token");
        }
        if (actor.type() != Actor.Type.USER || actor.userId() == null) {
            return null;
        }
        Optional<Membership> m = memberships.findByOrganizationIdAndUserId(org.getId(), actor.userId());
        if (m.isPresent() && m.get().isActive()) {
            return new Resolved(permissionsOf(org.getId(), m.get().getRoleCode()), "membership");
        }
        if (actor.isStaff()) {
            rls.enterOrg(org.getId());
            Optional<String> level = support.activeLevel(org.getId(), actor.userId(), Instant.now());
            if (level.isPresent()) {
                return new Resolved("OPERATE".equals(level.get())
                        ? OrgPermissions.SUPPORT_OPERATE : OrgPermissions.SUPPORT_READ, "support_access");
            }
        }
        return null;
    }

    public Set<String> permissionsOf(UUID orgId, String roleCode) {
        return roles.resolve(orgId, roleCode).stream().findFirst()
                .map(OrgRole::getPermissions)
                .map(Set::copyOf)
                .orElse(Set.of());
    }

    private void deny(UUID orgId, Actor actor, String permission, String reason) {
        boolean orgExists = orgs.existsById(orgId);
        audit.recordIndependently(orgExists ? orgId : null, actor,
                AuditService.Entry.denied("access.denied", "organization", orgId,
                        Map.of("permission", permission == null ? "" : permission, "reason", reason)));
    }
}
