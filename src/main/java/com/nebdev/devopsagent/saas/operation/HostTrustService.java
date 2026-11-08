package com.nebdev.devopsagent.saas.operation;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.server.Server;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Confiance dans la clé d'hôte (anti-interception à la première connexion) : l'empreinte est lue, montrée,
 * puis confirmée explicitement par l'utilisateur ; à la confirmation elle est relue et doit être identique.
 */
@Service
public class HostTrustService {

    private final OrgAccess access;
    private final ServerService servers;
    private final EnginePort engine;
    private final AuditService audit;
    private final RlsContext rls;
    private final TransactionTemplate tx;
    private final TargetResolver resolver;

    public HostTrustService(OrgAccess access, ServerService servers, EnginePort engine, AuditService audit,
                            RlsContext rls, PlatformTransactionManager txManager, TargetResolver resolver) {
        this.resolver = resolver;
        this.access = access;
        this.servers = servers;
        this.engine = engine;
        this.audit = audit;
        this.rls = rls;
        this.tx = new TransactionTemplate(txManager);
    }

    /** La lecture réseau se fait hors transaction (pas de connexion base retenue pendant l'attente SSH). */
    public Map<String, Object> scan(UUID orgId, UUID serverId) {
        EnginePort.Target target = tx.execute(s -> {
            access.require(orgId, OrgPermissions.SERVER_MANAGE);
            Server server = servers.require(orgId, serverId);
            if ("BLOCKED".equals(server.getProtection())) {
                throw ApiException.unprocessable("server_blocked");
            }
            return OperationService.target(server);
        });
        String fp = read(target);
        return Map.of("fingerprint", fp, "host", target.host(), "port", target.port());
    }

    public Map<String, Object> trust(UUID orgId, UUID serverId, String confirmedFingerprint) {
        if (confirmedFingerprint == null || !confirmedFingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) {
            throw ApiException.badRequest("invalid_fingerprint");
        }
        EnginePort.Target target = tx.execute(s -> {
            access.require(orgId, OrgPermissions.SERVER_MANAGE);
            return OperationService.target(servers.require(orgId, serverId));
        });
        String seen = read(target);
        if (!seen.equals(confirmedFingerprint)) {
            throw ApiException.conflict("fingerprint_mismatch");
        }
        tx.executeWithoutResult(s -> {
            access.require(orgId, OrgPermissions.SERVER_MANAGE);
            Server server = servers.require(orgId, serverId);
            String previous = server.getHostFingerprint();
            server.trustFingerprint(seen);
            audit.record(orgId, AuditService.Entry.success("server.host_key_trusted", "server", serverId,
                    Map.of("fingerprint", seen, "previous", previous == null ? "" : previous)));
        });
        return Map.of("fingerprint", seen, "trusted", true);
    }

    private String read(EnginePort.Target target) {
        EnginePort.Target pinned;
        try {
            pinned = resolver.pinned(target);
        } catch (com.nebdev.devopsagent.saas.shared.TargetPolicy.Refused e) {
            if ("server_unreachable".equals(e.code())) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "server_unreachable");
            }
            throw ApiException.unprocessable(e.code(), target.host());
        }
        try {
            return engine.scanHostKey(pinned);
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "server_unreachable");
        }
    }
}
