package com.nebdev.devopsagent.saas.server;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.shared.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ServerService {

    /** Port vers la facturation : le module serveur ne connaît pas les plans (directive §14). */
    public interface ServerQuotaPolicy {
        void assertCanAddServer(UUID organizationId, long currentCount);
    }

    public interface Servers extends JpaRepository<Server, UUID> {
        List<Server> findByOrganizationIdOrderByName(UUID organizationId);

        long countByOrganizationId(UUID organizationId);

        Optional<Server> findByIdAndOrganizationId(UUID id, UUID organizationId);

        boolean existsByOrganizationIdAndHostAndSshPortAndSshUser(UUID org, String host, int port, String user);
    }

    public interface Snapshots extends JpaRepository<ServerSnapshot, UUID> {
        @Query("select s from ServerSnapshot s where s.serverId = :server order by s.takenAt desc")
        List<ServerSnapshot> latest(@Param("server") UUID serverId, org.springframework.data.domain.Pageable p);

        @Query(value = "delete from server_snapshot where server_id = :server and id not in "
                + "(select id from server_snapshot where server_id = :server order by taken_at desc limit :keep)",
                nativeQuery = true)
        @org.springframework.data.jpa.repository.Modifying
        int prune(@Param("server") UUID serverId, @Param("keep") int keep);
    }

    static final int SNAPSHOTS_KEPT = 30;

    private final Servers servers;
    private final Snapshots snapshots;
    private final OrgAccess access;
    private final ServerQuotaPolicy quota;
    private final AuditService audit;
    private final ActiveExecutionCheck activeExecutions;

    private final com.nebdev.devopsagent.saas.shared.SaasProperties.Servers targets;

    public ServerService(Servers servers, Snapshots snapshots, OrgAccess access, ServerQuotaPolicy quota,
                         AuditService audit, ActiveExecutionCheck activeExecutions,
                         com.nebdev.devopsagent.saas.shared.SaasProperties properties) {
        this.targets = properties.getServers();
        this.activeExecutions = activeExecutions;
        this.servers = servers;
        this.snapshots = snapshots;
        this.access = access;
        this.quota = quota;
        this.audit = audit;
    }

    @Transactional
    public Server create(UUID orgId, String name, String host, int port, String user) {
        access.require(orgId, OrgPermissions.SERVER_MANAGE);
        Server s;
        try {
            s = new Server(orgId, name, host, port, user);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_server", e.getMessage());
        }
        checkTarget(s.getHost());
        if (servers.existsByOrganizationIdAndHostAndSshPortAndSshUser(orgId, s.getHost(), s.getSshPort(), s.getSshUser())) {
            throw ApiException.conflict("server_exists");
        }
        quota.assertCanAddServer(orgId, servers.countByOrganizationId(orgId));
        servers.save(s);
        audit.record(orgId, AuditService.Entry.success("server.created", "server", s.getId(),
                Map.of("host", s.getHost(), "port", s.getSshPort(), "user", s.getSshUser())));
        return s;
    }

    /**
     * Migration (ADR-057) : serveur repris, sans quota (parc existant) mais avec le contrôle des cibles. Empreinte
     * d'hôte non reprise : elle doit être confirmée à nouveau (l'ancienne plateforme stockait des empreintes MD5).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Server createForImport(UUID orgId, String name, String host, int port, String user) {
        Server s = new Server(orgId, name, host, port, user);
        checkTarget(s.getHost());
        if (servers.existsByOrganizationIdAndHostAndSshPortAndSshUser(orgId, s.getHost(), s.getSshPort(), s.getSshUser())) {
            return servers.findByOrganizationIdOrderByName(orgId).stream()
                    .filter(x -> x.getHost().equals(s.getHost()) && x.getSshPort() == s.getSshPort()
                            && x.getSshUser().equals(s.getSshUser())).findFirst().orElseThrow();
        }
        servers.save(s);
        audit.record(orgId, null, AuditService.Entry.success("server.imported", "server", s.getId(),
                Map.of("host", s.getHost(), "port", s.getSshPort(), "user", s.getSshUser(), "source", "platform")));
        return s;
    }

    /** Anti-SSRF : aucune cible interne à la plateforme (voir {@link com.nebdev.devopsagent.saas.shared.TargetPolicy}). */
    private void checkTarget(String host) {
        try {
            com.nebdev.devopsagent.saas.shared.TargetPolicy.checkRegistration(host, targets.isAllowPrivateTargets(),
                    targets.denied());
        } catch (com.nebdev.devopsagent.saas.shared.TargetPolicy.Refused e) {
            throw ApiException.unprocessable(e.code(), host);
        }
    }

    @Transactional(readOnly = true)
    public List<Server> list(UUID orgId) {
        access.require(orgId, OrgPermissions.SERVER_READ);
        return servers.findByOrganizationIdOrderByName(orgId);
    }

    @Transactional(readOnly = true)
    public Server get(UUID orgId, UUID serverId) {
        access.require(orgId, OrgPermissions.SERVER_READ);
        return require(orgId, serverId);
    }

    @Transactional
    public Server update(UUID orgId, UUID serverId, String name, String host, Integer port, String user, String protection) {
        access.require(orgId, OrgPermissions.SERVER_MANAGE);
        Server s = require(orgId, serverId);
        Map<String, Object> changes = new LinkedHashMap<>();
        try {
            if (name != null) {
                s.rename(name);
                changes.put("name", s.getName());
            }
            if (host != null || port != null || user != null) {
                s.moveTo(host, port, user);
                checkTarget(s.getHost());
                changes.put("target", s.getSshUser() + "@" + s.getHost() + ":" + s.getSshPort());
            }
            if (protection != null) {
                s.setProtection(protection);
                changes.put("protection", protection);
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_server", e.getMessage());
        }
        audit.record(orgId, AuditService.Entry.success("server.updated", "server", serverId, changes));
        return s;
    }

    @Transactional
    public void delete(UUID orgId, UUID serverId) {
        access.require(orgId, OrgPermissions.SERVER_MANAGE);
        Server s = require(orgId, serverId);
        if (activeExecutions.hasActiveExecution(serverId)) {
            throw ApiException.conflict("server_busy");
        }
        servers.delete(s);
        audit.record(orgId, AuditService.Entry.success("server.deleted", "server", serverId,
                Map.of("host", s.getHost())));
    }

    public interface ActiveExecutionCheck {
        boolean hasActiveExecution(UUID serverId);
    }

    @Transactional(readOnly = true)
    public Optional<ServerSnapshot> latestSnapshot(UUID orgId, UUID serverId) {
        access.require(orgId, OrgPermissions.SERVER_READ);
        require(orgId, serverId);
        return snapshots.latest(serverId, PageRequest.of(0, 1)).stream().findFirst();
    }

    /** Appelé par le moteur (contexte RLS déjà posé par le worker) après une analyse réussie. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ServerSnapshot recordSnapshot(Server server, Map<String, Object> payload, Integer score, String grade,
                                         String osFamily, String osVersion, Map<String, Object> caps, String engineVersion) {
        Instant now = Instant.now();
        ServerSnapshot snap = snapshots.save(new ServerSnapshot(server.getOrganizationId(), server.getId(), now,
                payload, score, grade));
        server.observed("REACHABLE", osFamily, osVersion, caps, engineVersion, now);
        snapshots.prune(server.getId(), SNAPSHOTS_KEPT);
        return snap;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ServerSnapshot> latestSnapshotUnchecked(UUID serverId) {
        return snapshots.latest(serverId, PageRequest.of(0, 1)).stream().findFirst();
    }

    /** Recherche sous contexte RLS : un serveur d'une autre organisation n'existe pas (404). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Server require(UUID orgId, UUID serverId) {
        return servers.findByIdAndOrganizationId(serverId, orgId).orElseThrow(() -> ApiException.notFound("server"));
    }

    public static Map<String, Object> view(Server s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("organizationId", s.getOrganizationId());
        m.put("name", s.getName());
        m.put("host", s.getHost());
        m.put("sshPort", s.getSshPort());
        m.put("sshUser", s.getSshUser());
        m.put("osFamily", s.getOsFamily());
        m.put("osVersion", s.getOsVersion());
        m.put("connectionState", s.getConnectionState());
        m.put("lastCheckedAt", s.getLastCheckedAt());
        m.put("engineVersion", s.getEngineVersion());
        m.put("capabilities", s.getCapabilities());
        m.put("maintenanceState", s.getMaintenanceState());
        m.put("protection", s.getProtection());
        m.put("hostFingerprint", s.getHostFingerprint());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }
}
