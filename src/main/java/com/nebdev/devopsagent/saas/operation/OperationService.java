package com.nebdev.devopsagent.saas.operation;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.credential.CredentialService;
import com.nebdev.devopsagent.saas.deployment.DeploymentSpec;
import com.nebdev.devopsagent.saas.deployment.DeploymentSpecService;
import com.nebdev.devopsagent.saas.staging.StagingService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.server.Server;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.server.ServerSnapshot;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.Hashing;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Cycle de vie des opérations (ADR-050) :
 * <pre>
 * READ   : propose → QUEUED → RUNNING → SUCCEEDED | FAILED           (approbation implicite)
 * CHANGE : propose → PROPOSED → approve(hash) → APPROVED → QUEUED → RUNNING → SUCCEEDED | FAILED
 *                            ↘ reject → REJECTED   ↘ cancel → CANCELLED   ↘ délai → EXPIRED
 * DESTRUCTIVE : refusé.
 * </pre>
 */
@Service
public class OperationService {

    public static final String ORIGIN_USER = "USER";
    public static final String ORIGIN_API = "API";
    public static final String ORIGIN_AI = "AI_SUGGESTION";
    public static final String ORIGIN_MANAGED = "MANAGED";

    private final OperationRepositories.Operations operations;
    private final OperationRepositories.Approvals approvals;
    private final OperationRepositories.Executions executions;
    private final OrgAccess access;
    private final ServerService servers;
    private final CredentialService credentials;
    private final DeploymentSpecService deployments;
    private final StagingService staging;
    private final EnginePort engine;
    private final AuditService audit;
    private final SaasProperties properties;

    public OperationService(OperationRepositories.Operations operations, OperationRepositories.Approvals approvals,
                            OperationRepositories.Executions executions, OrgAccess access, ServerService servers,
                            CredentialService credentials, DeploymentSpecService deployments, StagingService staging,
                            EnginePort engine, AuditService audit, SaasProperties properties) {
        this.operations = operations;
        this.approvals = approvals;
        this.executions = executions;
        this.access = access;
        this.servers = servers;
        this.credentials = credentials;
        this.deployments = deployments;
        this.staging = staging;
        this.engine = engine;
        this.audit = audit;
        this.properties = properties;
    }

    @Transactional
    public OperationEntities.Operation propose(UUID orgId, UUID serverId, String rawType, Map<String, Object> parameters,
                                               String reason, String idempotencyKey) {
        OperationType type = OperationType.parse(rawType)
                .orElseThrow(() -> ApiException.badRequest("unknown_operation_type", rawType));
        if (type.risk() == OperationType.Risk.DESTRUCTIVE) {
            throw ApiException.unprocessable("destructive_blocked");
        }
        OrgAccess.OrgContext ctx = access.require(orgId, type.proposePermission());
        return proposeInContext(ctx, serverId, type, parameters, reason, idempotencyKey, defaultOrigin(ctx.actor()), false);
    }

    /** Brouillon d'opération (suggestion IA) : planifié et haché, mais jamais exécutable sans proposition humaine. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OperationEntities.Operation draftInContext(OrgAccess.OrgContext ctx, UUID serverId, OperationType type,
                                                      Map<String, Object> parameters, String reason) {
        return proposeInContext(ctx, serverId, type, parameters, reason, null, ORIGIN_AI, true);
    }

    private OperationEntities.Operation proposeInContext(OrgAccess.OrgContext ctx, UUID serverId, OperationType type,
                                                         Map<String, Object> parameters, String reason,
                                                         String idempotencyKey, String origin, boolean draft) {
        UUID orgId = ctx.orgId();
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 128)) {
            throw ApiException.badRequest("invalid_idempotency_key");
        }
        if (idempotencyKey != null) {
            Optional<OperationEntities.Operation> existing = operations.findByOrganizationIdAndIdempotencyKey(orgId, idempotencyKey);
            if (existing.isPresent()) {
                OperationEntities.Operation e = existing.get();
                if (!e.getServerId().equals(serverId) || !e.getType().equals(type.name())) {
                    throw ApiException.conflict("idempotency_key_reused");
                }
                return e;
            }
        }
        Server server = servers.require(orgId, serverId);
        if ("BLOCKED".equals(server.getProtection())) {
            throw ApiException.unprocessable("server_blocked");
        }
        if ("READ_ONLY".equals(server.getProtection()) && type.risk() != OperationType.Risk.READ) {
            throw ApiException.unprocessable("server_read_only");
        }
        if (server.getHostFingerprint() == null) {
            throw ApiException.unprocessable("host_key_not_trusted");
        }
        if (credentials.publicKeyOf(orgId, serverId, "ACTIVE").isEmpty()) {
            throw ApiException.unprocessable("no_active_credential");
        }
        Map<String, Object> params = parameters == null ? Map.of() : Map.copyOf(parameters);
        if (type == OperationType.DEPLOY_PLATFORM || type == OperationType.PLATFORM_SQL_READ
                || type == OperationType.PLATFORM_SQL_WRITE || type == OperationType.RESTORE_PLATFORM
                || type == OperationType.SET_PLATFORM_SECRET || type == OperationType.UNSET_PLATFORM_SECRET) {
            params = deployments.freezeForOperation(orgId, serverId, DeploymentSpec.KIND_PLATFORM, params);
            if (type == OperationType.PLATFORM_SQL_READ || type == OperationType.PLATFORM_SQL_WRITE) {
                params = requireSql(type, params);
            } else if (type == OperationType.RESTORE_PLATFORM) {
                params = requireRestore(params);
                params = staging.bindForOperation(orgId, serverId, params);
            } else if (type == OperationType.SET_PLATFORM_SECRET) {
                params = requireSecretStaging(params);
                params = staging.bindForOperation(orgId, serverId, params);
            } else if (type == OperationType.UNSET_PLATFORM_SECRET) {
                params = requireUnsetSecret(params);
            }
        } else if (type == OperationType.DEPLOY_APP) {
            params = deployments.freezeForOperation(orgId, serverId, DeploymentSpec.KIND_APP, params);
        }
        String pendingPublic = null;
        if (type == OperationType.ROTATE_CREDENTIAL) {
            pendingPublic = credentials.publicKeyOf(orgId, serverId, "PENDING")
                    .orElseThrow(() -> ApiException.unprocessable("no_pending_credential"));
        }
        Map<String, Object> snapshot = servers.latestSnapshotUnchecked(serverId).map(ServerSnapshot::getPayload).orElse(null);
        EnginePort.Plan plan;
        try {
            plan = engine.plan(new EnginePort.PlanRequest(type, target(server), params, snapshot, pendingPublic));
        } catch (EnginePort.PlanRefused e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), Map.of("reason", e.getMessage()));
        }
        OperationEntities.Operation op = new OperationEntities.Operation(orgId, serverId, type, origin,
                ctx.actor().userId(), reason, params, idempotencyKey);
        Instant now = Instant.now();
        op.planned(PlanHash.document(plan, server, params), PlanHash.of(plan, server, params),
                now.plus(Duration.ofHours(properties.getOperations().getProposalTtlHours())));
        if (draft) {
            op.status("DRAFT");
            operations.save(op);
        } else if (type.autoApproved()) {
            op.status("APPROVED");
            operations.save(op);
            enqueue(op, null);
        } else {
            op.status("PROPOSED");
            operations.save(op);
        }
        audit.record(orgId, AuditService.Entry.success("operation." + (draft ? "drafted" : "proposed"), "operation",
                op.getId(), Map.of("type", type.name(), "risk", type.risk().name(), "serverId", serverId.toString(),
                        "planHash", op.getPlanHash(), "origin", origin)));
        return op;
    }

    /** Un brouillon (IA) devient une vraie proposition, à l'initiative d'un humain qui en prend la responsabilité. */
    @Transactional
    public OperationEntities.Operation submitDraft(UUID orgId, UUID opId) {
        access.enter(orgId); // contexte RLS avant toute lecture
        OperationEntities.Operation draft = require(orgId, opId);
        OrgAccess.OrgContext ctx = access.require(orgId, draft.typeEnum().proposePermission());
        if (!"DRAFT".equals(draft.getStatus())) {
            throw ApiException.conflict("invalid_state", draft.getStatus());
        }
        draft.status("CANCELLED");
        audit.record(orgId, AuditService.Entry.success("operation.draft_submitted", "operation", opId, Map.of()));
        return proposeInContext(ctx, draft.getServerId(), draft.typeEnum(), draft.getParameters(),
                draft.getReason(), null, defaultOrigin(ctx.actor()), false);
    }

    @Transactional
    public OperationEntities.Operation approve(UUID orgId, UUID opId, String planHash, String comment) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.OPERATION_APPROVE);
        OperationEntities.Operation op = requireForUpdate(orgId, opId);
        if (!"PROPOSED".equals(op.getStatus())) {
            throw ApiException.conflict("invalid_state", op.getStatus());
        }
        Instant now = Instant.now();
        if (op.getExpiresAt() != null && op.getExpiresAt().isBefore(now)) {
            op.status("EXPIRED");
            throw ApiException.conflict("operation_expired");
        }
        if (planHash == null || !planHash.equals(op.getPlanHash())) {
            throw ApiException.conflict("plan_hash_mismatch");
        }
        UUID approver = ctx.actor().userId();
        if (approver == null) {
            throw ApiException.forbidden("approver_required");
        }
        if (ctx.organization().isRequireFourEyes() && Objects.equals(approver, op.getInitiatorId())) {
            throw ApiException.forbidden("four_eyes_required");
        }
        OperationEntities.Approval approval = approvals.save(new OperationEntities.Approval(orgId, opId, approver,
                planHash, "APPROVED", comment, now.plus(Duration.ofMinutes(properties.getOperations().getApprovalTtlMinutes()))));
        op.status("APPROVED");
        audit.record(orgId, AuditService.Entry.success("operation.approved", "operation", opId,
                Map.of("planHash", planHash, "approvalId", approval.getId().toString(), "via", ctx.via(),
                        "expiresAt", approval.getExpiresAt().toString())));
        enqueue(op, approval);
        return op;
    }

    @Transactional
    public OperationEntities.Operation reject(UUID orgId, UUID opId, String comment) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.OPERATION_APPROVE);
        OperationEntities.Operation op = requireForUpdate(orgId, opId);
        if (!"PROPOSED".equals(op.getStatus())) {
            throw ApiException.conflict("invalid_state", op.getStatus());
        }
        approvals.save(new OperationEntities.Approval(orgId, opId, ctx.actor().userId(), op.getPlanHash(), "REJECTED",
                comment, Instant.now()));
        op.status("REJECTED");
        audit.record(orgId, AuditService.Entry.success("operation.rejected", "operation", opId,
                Map.of("comment", comment == null ? "" : comment)));
        return op;
    }

    @Transactional
    public OperationEntities.Operation cancel(UUID orgId, UUID opId) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.OPERATION_PROPOSE);
        OperationEntities.Operation op = requireForUpdate(orgId, opId);
        boolean own = Objects.equals(op.getInitiatorId(), ctx.actor().userId());
        if (!own && !ctx.can(OrgPermissions.OPERATION_APPROVE)) {
            throw ApiException.forbidden("missing_permission");
        }
        if (!List.of("DRAFT", "PROPOSED", "APPROVED").contains(op.getStatus())) {
            throw ApiException.conflict("invalid_state", op.getStatus());
        }
        op.status("CANCELLED");
        audit.record(orgId, AuditService.Entry.success("operation.cancelled", "operation", opId, Map.of()));
        return op;
    }

    @Transactional(readOnly = true)
    public List<OperationEntities.Operation> list(UUID orgId, UUID serverId, String status, int page, int size) {
        access.require(orgId, OrgPermissions.OPERATION_READ);
        return operations.search(orgId, serverId, status == null || status.isBlank() ? null : status.strip().toUpperCase(),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
    }

    public record Detail(OperationEntities.Operation operation, List<OperationEntities.Approval> approvals,
                         List<OperationEntities.Execution> executions) {}

    @Transactional(readOnly = true)
    public Detail get(UUID orgId, UUID opId) {
        access.require(orgId, OrgPermissions.OPERATION_READ);
        OperationEntities.Operation op = require(orgId, opId);
        return new Detail(op, approvals.findByOperationIdOrderByDecidedAt(opId), executions.findByOperationIdOrderByQueuedAt(opId));
    }

    /** Met en file si le serveur est libre ; sinon l'opération reste APPROVED et le répartiteur la reprendra. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(OperationEntities.Operation op, OperationEntities.Approval approval) {
        if (executions.activeOnServer(op.getServerId())) {
            return false;
        }
        int attempt = executions.findByOperationIdOrderByQueuedAt(op.getId()).size() + 1;
        executions.saveAndFlush(new OperationEntities.Execution(op.getOrganizationId(), op.getId(), op.getServerId(),
                approval == null ? null : approval.getId(), "op:" + op.getId() + ":" + attempt, attempt));
        op.status("QUEUED");
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OperationEntities.Approval> validApproval(UUID opId, Instant now) {
        List<OperationEntities.Approval> list = approvals.findByOperationIdOrderByDecidedAt(opId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1)).filter(a -> a.validAt(now));
    }

    private OperationEntities.Operation require(UUID orgId, UUID opId) {
        return operations.findByIdAndOrganizationId(opId, orgId).orElseThrow(() -> ApiException.notFound("operation"));
    }

    private OperationEntities.Operation requireForUpdate(UUID orgId, UUID opId) {
        return require(orgId, opId);
    }

    static EnginePort.Target target(Server s) {
        return new EnginePort.Target(s.getHost(), s.getSshPort(), s.getSshUser());
    }

    private static String defaultOrigin(Actor actor) {
        if (actor.type() == Actor.Type.API_TOKEN) {
            return ORIGIN_API;
        }
        return actor.isStaff() ? ORIGIN_MANAGED : ORIGIN_USER;
    }

    /**
     * Présence / taille / empreinte SQL seulement — le genre READ/WRITE est tranché par le moteur
     * ({@code SqlConsolePolicy}) pour respecter la frontière ArchUnit (seul {@code saas.engine} parle à {@code stack}).
     */
    private static Map<String, Object> requireSql(OperationType type, Map<String, Object> params) {
        Object raw = params.get("sql");
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw ApiException.badRequest("sql_required");
        }
        String sql = String.valueOf(raw).strip();
        if (sql.length() > 200_000) {
            throw ApiException.badRequest("sql_too_large");
        }
        Map<String, Object> next = new LinkedHashMap<>(params);
        next.put("sql", sql);
        next.put("sqlSha256", Hashing.sha256Hex(sql));
        next.put("operationSqlType", type.name());
        return Map.copyOf(next);
    }

    private static Map<String, Object> requireRestore(Map<String, Object> params) {
        boolean sql = params.get("sqlStagingId") != null && !String.valueOf(params.get("sqlStagingId")).isBlank();
        boolean uploads = params.get("uploadsStagingId") != null
                && !String.valueOf(params.get("uploadsStagingId")).isBlank();
        if (!sql && !uploads) {
            throw ApiException.badRequest("restore_staging_required");
        }
        return params;
    }

    private static Map<String, Object> requireSecretStaging(Map<String, Object> params) {
        if (params.get("secretStagingId") == null || String.valueOf(params.get("secretStagingId")).isBlank()) {
            throw ApiException.badRequest("secret_staging_required");
        }
        return params;
    }

    private static Map<String, Object> requireUnsetSecret(Map<String, Object> params) {
        Object serviceId = params.get("serviceId");
        Object name = params.get("secretName");
        if (serviceId == null || String.valueOf(serviceId).isBlank() || name == null || String.valueOf(name).isBlank()) {
            throw ApiException.badRequest("secret_target_required");
        }
        Map<String, Object> next = new LinkedHashMap<>(params);
        next.put("serviceId", String.valueOf(serviceId).trim().toLowerCase());
        next.put("secretName", String.valueOf(name).trim());
        if (params.get("restart") == null) {
            next.put("restart", true);
        }
        return Map.copyOf(next);
    }

    public static Map<String, Object> view(OperationEntities.Operation o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("serverId", o.getServerId());
        m.put("type", o.getType());
        m.put("label", o.typeEnum().label());
        m.put("risk", o.getRiskLevel());
        m.put("status", o.getStatus());
        m.put("origin", o.getOrigin());
        m.put("initiatorId", o.getInitiatorId());
        m.put("reason", o.getReason());
        m.put("parameters", o.getParameters());
        m.put("plan", o.getPlan());
        m.put("planHash", o.getPlanHash());
        m.put("expiresAt", o.getExpiresAt());
        m.put("createdAt", o.getCreatedAt());
        m.put("updatedAt", o.getUpdatedAt());
        return m;
    }

    public static Map<String, Object> view(OperationEntities.Approval a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("approverId", a.getApproverId());
        m.put("decision", a.getDecision());
        m.put("planHash", a.getPlanHash());
        m.put("comment", a.getComment());
        m.put("decidedAt", a.getDecidedAt());
        m.put("expiresAt", a.getExpiresAt());
        return m;
    }

    public static Map<String, Object> view(OperationEntities.Execution e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("status", e.getStatus());
        m.put("attempt", e.getAttempt());
        m.put("runnerId", e.getRunnerId());
        m.put("queuedAt", e.getQueuedAt());
        m.put("startedAt", e.getStartedAt());
        m.put("endedAt", e.getEndedAt());
        m.put("exitCode", e.getExitCode());
        m.put("output", e.getOutputExcerpt());
        m.put("outputSha256", e.getOutputSha256());
        m.put("error", e.getError());
        m.put("verification", e.getVerification());
        return m;
    }
}
