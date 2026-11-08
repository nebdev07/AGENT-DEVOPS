package com.nebdev.devopsagent.saas.managed;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.identity.IdentityRepositories;
import com.nebdev.devopsagent.saas.identity.UserAccount;
import com.nebdev.devopsagent.saas.notification.NotificationService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.organization.Organization;
import com.nebdev.devopsagent.saas.organization.OrganizationRepositories;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Offre Managed (ADR-052). Les techniciens INOVNE utilisent le <b>même</b> moteur et les mêmes opérations que les
 * clients ; ce module ne fait que dire qui peut agir, jusqu'à quand, sous quel contrat.
 */
@Service
public class ManagedService {

    public static final Set<String> LEVELS = Set.of("READ", "OPERATE");
    public static final Set<String> SERVICE_LEVELS = Set.of("ESSENTIAL", "BUSINESS", "CRITICAL");
    public static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    public static final Set<String> INTERVENTION_STATUSES = Set.of("OPEN", "PLANNED", "IN_PROGRESS", "DONE", "CANCELLED");
    static final int MAX_GRANT_HOURS = 24 * 30;

    public interface Grants extends JpaRepository<ManagedEntities.SupportGrant, UUID> {
        @Query("select g from SupportGrant g where g.organizationId = :org and g.staffUserId = :staff "
                + "and g.revokedAt is null and g.startsAt <= :now and g.expiresAt > :now order by g.accessLevel desc")
        List<ManagedEntities.SupportGrant> active(@Param("org") UUID org, @Param("staff") UUID staff, @Param("now") Instant now);

        List<ManagedEntities.SupportGrant> findByOrganizationIdOrderByCreatedAtDesc(UUID org);

        @Query("select g from SupportGrant g where g.staffUserId = :staff and g.revokedAt is null "
                + "and g.expiresAt > :now order by g.expiresAt")
        List<ManagedEntities.SupportGrant> activeForStaff(@Param("staff") UUID staff, @Param("now") Instant now);
    }

    public interface Contracts extends JpaRepository<ManagedEntities.Contract, UUID> {
        List<ManagedEntities.Contract> findByOrganizationIdOrderByCreatedAtDesc(UUID org);
    }

    public interface Interventions extends JpaRepository<ManagedEntities.Intervention, UUID> {
        List<ManagedEntities.Intervention> findByOrganizationIdOrderByCreatedAtDesc(UUID org);

        Optional<ManagedEntities.Intervention> findByIdAndOrganizationId(UUID id, UUID org);
    }

    private final Grants grants;
    private final Contracts contracts;
    private final Interventions interventions;
    private final IdentityRepositories.Users users;
    private final IdentityRepositories.Staff staff;
    private final OrganizationRepositories.Organizations orgs;
    private final ObjectProviderHolder access;
    private final ServerService servers;
    private final AuditService audit;
    private final NotificationService notifications;
    private final RlsContext rls;

    /** OrgAccess dépend de ce service (port) : résolution paresseuse pour éviter le cycle de construction. */
    public interface ObjectProviderHolder {
        OrgAccess get();
    }

    public ManagedService(Grants grants, Contracts contracts, Interventions interventions,
                          IdentityRepositories.Users users, IdentityRepositories.Staff staff,
                          OrganizationRepositories.Organizations orgs,
                          org.springframework.beans.factory.ObjectProvider<OrgAccess> access, ServerService servers,
                          AuditService audit, NotificationService notifications, RlsContext rls) {
        this.grants = grants;
        this.contracts = contracts;
        this.interventions = interventions;
        this.users = users;
        this.staff = staff;
        this.orgs = orgs;
        this.access = access::getObject;
        this.servers = servers;
        this.audit = audit;
        this.notifications = notifications;
        this.rls = rls;
    }

    // ───────────── Accès support (côté client) ─────────────

    @Transactional
    public ManagedEntities.SupportGrant grant(UUID orgId, String staffEmail, String level, int hours, String reason) {
        OrgAccess.OrgContext ctx = access.get().require(orgId, OrgPermissions.SUPPORT_GRANT);
        if (!LEVELS.contains(level)) {
            throw ApiException.badRequest("invalid_access_level");
        }
        if (hours < 1 || hours > MAX_GRANT_HOURS) {
            throw ApiException.badRequest("invalid_duration", MAX_GRANT_HOURS);
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw ApiException.badRequest("reason_required");
        }
        UserAccount technician = users.findByEmail(staffEmail == null ? "" : staffEmail.strip())
                .filter(u -> staff.existsById(u.getId()))
                .orElseThrow(() -> ApiException.notFound("staff"));
        ManagedEntities.SupportGrant g = grants.save(new ManagedEntities.SupportGrant(orgId, technician.getId(), level,
                reason.strip(), ctx.userId(), Instant.now().plus(Duration.ofHours(hours))));
        audit.record(orgId, AuditService.Entry.success("support.granted", "support_grant", g.getId(),
                Map.of("staff", technician.getEmail(), "level", level, "expiresAt", g.getExpiresAt().toString(),
                        "reason", g.getReason())));
        notifications.email(orgId, technician.getId(), technician.getEmail(), "support_granted", technician.getLocale(),
                ctx.organization().getName(), level, g.getExpiresAt().toString());
        return g;
    }

    @Transactional
    public void revokeGrant(UUID orgId, UUID grantId) {
        OrgAccess.OrgContext ctx = access.get().require(orgId, OrgPermissions.SUPPORT_GRANT);
        ManagedEntities.SupportGrant g = grants.findById(grantId).filter(x -> x.getOrganizationId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("support_grant"));
        g.revoke(ctx.userId());
        audit.record(orgId, AuditService.Entry.success("support.revoked", "support_grant", grantId, Map.of()));
    }

    @Transactional(readOnly = true)
    public List<ManagedEntities.SupportGrant> listGrants(UUID orgId) {
        access.get().require(orgId, OrgPermissions.MANAGED_READ);
        return grants.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    /** Côté technicien : les organisations qui lui ont ouvert un accès (métadonnées seulement). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myAccesses() {
        Actor actor = Actor.require();
        if (!actor.isStaff()) {
            throw ApiException.forbidden("staff_only");
        }
        rls.enterPlatform();
        Instant now = Instant.now();
        return grants.activeForStaff(actor.userId(), now).stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>(grantView(g));
            orgs.findById(g.getOrganizationId()).ifPresent(o -> m.put("organizationName", o.getName()));
            return m;
        }).toList();
    }

    // ───────────── Contrats (côté INOVNE) ─────────────

    @Transactional
    public ManagedEntities.Contract createContract(UUID orgId, String serviceLevel, LocalDate startsOn, LocalDate endsOn,
                                                   int responseMinutes, String scope) {
        Actor actor = requirePlatformAdmin();
        if (!SERVICE_LEVELS.contains(serviceLevel)) {
            throw ApiException.badRequest("invalid_service_level");
        }
        if (responseMinutes < 15 || responseMinutes > 7 * 24 * 60) {
            throw ApiException.badRequest("invalid_response_time");
        }
        rls.enterOrg(orgId);
        Organization org = orgs.findById(orgId).orElseThrow(() -> ApiException.notFound("organization"));
        contracts.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus())).forEach(c -> c.setStatus("ENDED"));
        ManagedEntities.Contract c = contracts.save(new ManagedEntities.Contract(orgId, serviceLevel,
                startsOn == null ? LocalDate.now() : startsOn, endsOn, responseMinutes, scope, actor.userId()));
        org.setCustomerType("MANAGED");
        audit.record(orgId, AuditService.Entry.success("managed.contract_created", "managed_contract", c.getId(),
                Map.of("serviceLevel", serviceLevel, "responseTimeMinutes", responseMinutes)));
        return c;
    }

    @Transactional(readOnly = true)
    public List<ManagedEntities.Contract> contracts(UUID orgId) {
        access.get().require(orgId, OrgPermissions.MANAGED_READ);
        return contracts.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    // ───────────── Interventions ─────────────

    @Transactional
    public ManagedEntities.Intervention requestIntervention(UUID orgId, UUID serverId, String title, String description,
                                                            String priority) {
        OrgAccess.OrgContext ctx = access.get().require(orgId, OrgPermissions.MANAGED_REQUEST);
        if (title == null || title.isBlank() || title.length() > 200) {
            throw ApiException.badRequest("invalid_title");
        }
        String p = priority == null ? "NORMAL" : priority.toUpperCase();
        if (!PRIORITIES.contains(p)) {
            throw ApiException.badRequest("invalid_priority");
        }
        if (serverId != null) {
            servers.require(orgId, serverId);
        }
        UUID contract = contracts.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus())).map(ManagedEntities.Contract::getId).findFirst().orElse(null);
        ManagedEntities.Intervention i = interventions.save(new ManagedEntities.Intervention(orgId, contract, serverId,
                title.strip(), description, p, ctx.userId()));
        audit.record(orgId, AuditService.Entry.success("managed.intervention_requested", "intervention", i.getId(),
                Map.of("priority", p, "title", i.getTitle())));
        return i;
    }

    /** Suivi par le technicien (accès support OPERATE) ou par un admin de l'organisation (annulation). */
    @Transactional
    public ManagedEntities.Intervention updateIntervention(UUID orgId, UUID id, String status, Instant plannedFor,
                                                           String report, List<UUID> operationIds) {
        OrgAccess.OrgContext ctx = access.get().require(orgId, OrgPermissions.MANAGED_READ);
        boolean technician = "support_access".equals(ctx.via()) && ctx.can(OrgPermissions.OPERATION_PROPOSE);
        boolean clientAdmin = ctx.can(OrgPermissions.MANAGED_REQUEST);
        if (!technician && !clientAdmin) {
            throw ApiException.forbidden("missing_permission");
        }
        if (!technician && status != null && !"CANCELLED".equals(status)) {
            throw ApiException.forbidden("technician_only");
        }
        if (status != null && !INTERVENTION_STATUSES.contains(status)) {
            throw ApiException.badRequest("invalid_status");
        }
        ManagedEntities.Intervention i = interventions.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> ApiException.notFound("intervention"));
        i.update(status, technician ? ctx.userId() : null, plannedFor, technician ? report : null);
        if (operationIds != null) {
            for (UUID op : operationIds) {
                rlsInsertLink(i, op);
            }
        }
        audit.record(orgId, AuditService.Entry.success("managed.intervention_updated", "intervention", id,
                Map.of("status", i.getStatus())));
        return i;
    }

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    private void rlsInsertLink(ManagedEntities.Intervention i, UUID operationId) {
        Number found = (Number) em.createNativeQuery("select count(*) from operation where id = :op and organization_id = :org")
                .setParameter("op", operationId).setParameter("org", i.getOrganizationId()).getSingleResult();
        if (found.intValue() == 0) {
            throw ApiException.notFound("operation");
        }
        em.createNativeQuery("insert into intervention_operation (intervention_id, operation_id, organization_id) "
                        + "values (:i, :op, :org) on conflict do nothing")
                .setParameter("i", i.getId()).setParameter("op", operationId).setParameter("org", i.getOrganizationId())
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<ManagedEntities.Intervention> interventions(UUID orgId) {
        access.get().require(orgId, OrgPermissions.MANAGED_READ);
        return interventions.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    @SuppressWarnings("unchecked")
    public List<UUID> linkedOperations(UUID interventionId) {
        return ((List<Object>) em.createNativeQuery("select operation_id from intervention_operation where intervention_id = :i")
                .setParameter("i", interventionId).getResultList()).stream().map(o -> (UUID) o).toList();
    }

    private static Actor requirePlatformAdmin() {
        Actor actor = Actor.require();
        if (!actor.isPlatformAdmin()) {
            throw ApiException.forbidden("platform_admin_only");
        }
        return actor;
    }

    public static Map<String, Object> grantView(ManagedEntities.SupportGrant g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("organizationId", g.getOrganizationId());
        m.put("staffUserId", g.getStaffUserId());
        m.put("accessLevel", g.getAccessLevel());
        m.put("reason", g.getReason());
        m.put("grantedBy", g.getGrantedBy());
        m.put("startsAt", g.getStartsAt());
        m.put("expiresAt", g.getExpiresAt());
        m.put("revokedAt", g.getRevokedAt());
        m.put("active", g.activeAt(Instant.now()));
        return m;
    }

    public static Map<String, Object> contractView(ManagedEntities.Contract c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("serviceLevel", c.getServiceLevel());
        m.put("status", c.getStatus());
        m.put("startsOn", c.getStartsOn());
        m.put("endsOn", c.getEndsOn());
        m.put("responseTimeMinutes", c.getResponseTimeMinutes());
        m.put("scope", c.getScope());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    public static Map<String, Object> interventionView(ManagedEntities.Intervention i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("contractId", i.getContractId());
        m.put("serverId", i.getServerId());
        m.put("title", i.getTitle());
        m.put("description", i.getDescription());
        m.put("priority", i.getPriority());
        m.put("status", i.getStatus());
        m.put("requestedBy", i.getRequestedBy());
        m.put("assignedTo", i.getAssignedTo());
        m.put("plannedFor", i.getPlannedFor());
        m.put("closedAt", i.getClosedAt());
        m.put("report", i.getReport());
        m.put("createdAt", i.getCreatedAt());
        m.put("updatedAt", i.getUpdatedAt());
        return m;
    }
}
