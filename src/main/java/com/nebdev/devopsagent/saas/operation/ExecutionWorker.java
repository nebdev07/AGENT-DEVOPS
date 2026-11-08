package com.nebdev.devopsagent.saas.operation;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.credential.CredentialService;
import com.nebdev.devopsagent.saas.credential.GitCredentialService;
import com.nebdev.devopsagent.saas.staging.StagingService;
import com.nebdev.devopsagent.saas.server.Server;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.Hashing;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Exécuteur des opérations approuvées (mode {@code embedded}). Une file en base, partagée entre instances :
 * <ol>
 *   <li>réservation atomique ({@code FOR UPDATE SKIP LOCKED}) → RUNNING ;</li>
 *   <li>contrôles juste avant d'agir : approbation encore valide, identifiants, empreinte d'hôte ;</li>
 *   <li>exécution par le moteur <b>hors transaction</b> (peut durer longtemps), avec battement de cœur ;</li>
 *   <li>résultat, preuve (sortie tronquée + SHA-256), vérification, audit — dans une nouvelle transaction.</li>
 * </ol>
 * Une exécution dont le battement s'arrête (redémarrage) passe en FAILED « runner_lost » : jamais de reprise
 * automatique d'un CHANGE (l'état du serveur est inconnu, un humain doit relancer).
 */
@Component
public class ExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWorker.class);

    private final OperationRepositories.Operations operations;
    private final OperationRepositories.Executions executions;
    private final OperationService operationService;
    private final ServerService servers;
    private final CredentialService credentials;
    private final GitCredentialService gitCredentials;
    private final StagingService staging;
    private final EnginePort engine;
    private final AuditService audit;
    private final RlsContext rls;
    private final SaasProperties properties;
    private final TransactionTemplate tx;
    private final ExecutorService pool;
    private final Semaphore slots;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final String runnerId;
    private final TargetResolver resolver;

    public ExecutionWorker(OperationRepositories.Operations operations, OperationRepositories.Executions executions,
                           OperationService operationService, ServerService servers, CredentialService credentials,
                           GitCredentialService gitCredentials, StagingService staging, EnginePort engine,
                           AuditService audit, RlsContext rls, SaasProperties properties,
                           PlatformTransactionManager txManager, TargetResolver resolver) {
        this.resolver = resolver;
        this.operations = operations;
        this.executions = executions;
        this.operationService = operationService;
        this.servers = servers;
        this.credentials = credentials;
        this.gitCredentials = gitCredentials;
        this.staging = staging;
        this.engine = engine;
        this.audit = audit;
        this.rls = rls;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int threads = Math.max(1, properties.getRunner().getThreads());
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "autopilot-exec");
            t.setDaemon(true);
            return t;
        });
        this.slots = new Semaphore(threads);
        this.runnerId = "embedded@" + hostname();
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    /** Boucle principale : répartit les opérations approuvées en attente, puis réserve des exécutions. */
    @Scheduled(fixedDelayString = "${autopilot.runner.poll-millis:2000}", initialDelay = 3000)
    public void tick() {
        if (!properties.getRunner().isScheduling()) {
            return;
        }
        // Mise en file dans les deux modes ; en remote, ce sont les runners séparés qui réservent (API interne).
        dispatchApproved();
        if (!properties.getRunner().isEmbedded()) {
            return;
        }
        while (slots.tryAcquire()) {
            Optional<UUID> claimed;
            try {
                claimed = claim(runnerId);
            } catch (RuntimeException e) {
                slots.release();
                log.warn("Réservation d'exécution impossible : {}", e.getMessage());
                return;
            }
            if (claimed.isEmpty()) {
                slots.release();
                return;
            }
            UUID id = claimed.get();
            running.add(id);
            pool.submit(() -> {
                try {
                    process(id);
                } finally {
                    running.remove(id);
                    slots.release();
                }
            });
        }
    }

    /** Exécution synchrone d'une tâche déjà réservée (tests, runner distant). */
    public void runClaimedNow() {
        if (!properties.getRunner().isEmbedded()) {
            throw new IllegalStateException("Mode remote : l'exécution appartient au runner séparé.");
        }
        claim(runnerId).ifPresent(this::process);
    }

    // ───────────── Runner séparé (mode remote, ADR-055) ─────────────

    /**
     * Réserve la prochaine exécution pour un runner distant et lui remet un travail scellé. Les contrôles sont
     * les mêmes qu'en embedded ; un refus termine l'exécution en échec (et l'on tente la suivante).
     */
    public Optional<RunnerContract.Job> claimForRunner(String remoteRunnerId) {
        requireRemote();
        String rid = "remote:" + sanitize(remoteRunnerId);
        for (int i = 0; i < 5; i++) {
            Optional<UUID> claimed = claim(rid);
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            UUID id = claimed.get();
            try {
                RunnerContract.Job job = tx.execute(s -> prepareRemote(id, rid));
                if (job != null) {
                    return Optional.of(job);
                }
            } catch (RuntimeException e) {
                log.warn("Préparation de l'exécution {} refusée : {}", id, e.getMessage());
                finish(id, new EnginePort.Result(false, null, "", "preparation_failed: " + e.getMessage(), Map.of(),
                        null, false), null);
            }
        }
        return Optional.empty();
    }

    /** Battement de cœur d'un runner distant ; {@code false} si l'exécution ne lui appartient plus. */
    public boolean runnerHeartbeat(UUID executionId, String remoteRunnerId) {
        requireRemote();
        String rid = "remote:" + sanitize(remoteRunnerId);
        Boolean ok = tx.execute(s -> {
            rls.enterPlatform();
            return executions.findById(executionId)
                    .filter(e -> "RUNNING".equals(e.getStatus()) && rid.equals(e.getRunnerId()))
                    .map(e -> executions.heartbeat(List.of(executionId), Instant.now()) > 0)
                    .orElse(false);
        });
        return Boolean.TRUE.equals(ok);
    }

    /** Compte rendu d'un runner distant ; refusé si l'exécution n'est plus RUNNING ou appartient à un autre runner. */
    public boolean runnerResult(UUID executionId, String remoteRunnerId, RunnerContract.JobResult result) {
        requireRemote();
        String rid = "remote:" + sanitize(remoteRunnerId);
        Boolean owned = tx.execute(s -> {
            rls.enterPlatform();
            return executions.findById(executionId)
                    .map(e -> "RUNNING".equals(e.getStatus()) && rid.equals(e.getRunnerId())).orElse(false);
        });
        if (!Boolean.TRUE.equals(owned)) {
            return false;
        }
        finish(executionId, result.toResult(), result.engineVersion());
        return true;
    }

    private RunnerContract.Job prepareRemote(UUID executionId, String rid) {
        Checked c = check(executionId);
        UUID orgId = c.orgId();
        Server server = c.server();
        OperationType type = c.op().typeEnum();
        CredentialService.Sealed active = credentials.sealedFor(orgId, server.getId(), "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("no_active_credential"));
        Optional<CredentialService.Sealed> pending = type == OperationType.ROTATE_CREDENTIAL
                ? credentials.sealedFor(orgId, server.getId(), "PENDING") : Optional.empty();
        if (type == OperationType.ROTATE_CREDENTIAL && pending.isEmpty()) {
            throw new IllegalStateException("no_pending_credential");
        }
        String backup = type == OperationType.RUN_BACKUP || type == OperationType.INSTALL_BACKUPS
                ? credentials.sealedBackupPassphrase(orgId) : null;
        Map<String, Object> params = c.op().getParameters() == null ? Map.of() : c.op().getParameters();
        Map<String, RunnerContract.SealedValue> gitKeys = sealGitKeys(orgId, type, params);
        Map<String, RunnerContract.SealedStaging> sealedStaging = sealStaging(orgId, type, params);
        audit.record(orgId, system(), AuditService.Entry.success("operation.started", "operation", c.op().getId(),
                Map.of("executionId", executionId.toString(), "runner", rid)));
        return new RunnerContract.Job(executionId, orgId, c.op().getId(), type, OperationService.target(server),
                PlanHash.HashTarget.of(server), params, c.op().getPlanHash(), sealed(active),
                pending.map(ExecutionWorker::sealed).orElse(null), backup, gitKeys, sealedStaging);
    }

    private Map<String, RunnerContract.SealedValue> sealGitKeys(UUID orgId, OperationType type,
                                                                Map<String, Object> params) {
        if (type != OperationType.DEPLOY_PLATFORM && type != OperationType.DEPLOY_APP) {
            return Map.of();
        }
        Map<String, RunnerContract.SealedValue> out = new LinkedHashMap<>();
        gitCredentials.sealForDeploy(orgId, params).forEach((keyId, s) ->
                out.put(keyId, new RunnerContract.SealedValue(s.id(), s.sealedBase64(), s.publicKey())));
        return Map.copyOf(out);
    }

    private Map<String, RunnerContract.SealedStaging> sealStaging(UUID orgId, OperationType type,
                                                                  Map<String, Object> params) {
        if (type != OperationType.RESTORE_PLATFORM && type != OperationType.SET_PLATFORM_SECRET) {
            return Map.of();
        }
        Map<String, RunnerContract.SealedStaging> out = new LinkedHashMap<>();
        for (String key : List.of("sqlStagingId", "uploadsStagingId", "secretStagingId")) {
            Object raw = params.get(key);
            if (raw == null || String.valueOf(raw).isBlank()) {
                continue;
            }
            UUID id = UUID.fromString(String.valueOf(raw));
            StagingService.Sealed s = staging.sealForRunner(orgId, id);
            out.put(key, new RunnerContract.SealedStaging(s.id(), s.kind(), s.originalName(), s.sealedBase64(),
                    s.contentSha256()));
        }
        return Map.copyOf(out);
    }

    private static RunnerContract.SealedValue sealed(CredentialService.Sealed s) {
        return new RunnerContract.SealedValue(s.credentialId(), s.sealedBase64(), s.publicKey());
    }

    private void requireRemote() {
        if (properties.getRunner().isEmbedded()) {
            throw new IllegalStateException("runner_api_disabled");
        }
    }

    private static String sanitize(String id) {
        String v = id == null ? "" : id.replaceAll("[^A-Za-z0-9@._:-]", "");
        return v.isEmpty() ? "anonymous" : v.substring(0, Math.min(80, v.length()));
    }

    public void dispatchApproved() {
        List<OperationEntities.Operation> waiting = tx.execute(s -> {
            rls.enterPlatform();
            return new ArrayList<>(operations.approvedWaiting(PageRequest.of(0, 50)));
        });
        if (waiting == null) {
            return;
        }
        Instant now = Instant.now();
        for (OperationEntities.Operation w : waiting) {
            try {
                tx.executeWithoutResult(s -> {
                    rls.enterOrg(w.getOrganizationId());
                    OperationEntities.Operation op = operations.findById(w.getId()).orElse(null);
                    if (op == null || !"APPROVED".equals(op.getStatus())) {
                        return;
                    }
                    Optional<OperationEntities.Approval> approval = op.typeEnum().autoApproved()
                            ? Optional.empty() : operationService.validApproval(op.getId(), now);
                    if (!op.typeEnum().autoApproved() && approval.isEmpty()) {
                        op.status("EXPIRED");
                        audit.record(op.getOrganizationId(), system(), AuditService.Entry.failure("operation.expired",
                                "operation", op.getId(), Map.of("reason", "approval_expired")));
                        return;
                    }
                    operationService.enqueue(op, approval.orElse(null));
                });
            } catch (RuntimeException e) {
                log.debug("Mise en file différée pour {} : {}", w.getId(), e.getMessage());
            }
        }
    }

    Optional<UUID> claim(String claimant) {
        return Optional.ofNullable(tx.execute(s -> {
            rls.enterPlatform();
            Optional<UUID> id = executions.claimNext();
            if (id.isEmpty()) {
                return null;
            }
            OperationEntities.Execution e = executions.findById(id.get()).orElseThrow();
            e.start(claimant, Instant.now());
            operations.findById(e.getOperationId()).ifPresent(op -> op.status("RUNNING"));
            return e.getId();
        }));
    }

    private record Prepared(EnginePort.ExecutionRequest request, UUID orgId, UUID operationId, UUID serverId,
                            OperationType type) {}

    void process(UUID executionId) {
        Prepared prepared;
        try {
            prepared = tx.execute(s -> prepare(executionId));
        } catch (RuntimeException e) {
            log.warn("Préparation de l'exécution {} refusée : {}", executionId, e.getMessage());
            finish(executionId, new EnginePort.Result(false, null, "", "preparation_failed: " + e.getMessage(),
                    Map.of(), null, false), engine.version());
            return;
        }
        if (prepared == null) {
            return;
        }
        EnginePort.Result result;
        try {
            result = engine.execute(prepared.request());
        } catch (RuntimeException e) {
            log.error("Moteur : exception non gérée pour {}", executionId, e);
            result = new EnginePort.Result(false, null, "", "engine_error: " + e.getClass().getSimpleName(), Map.of(),
                    null, false);
        }
        finish(executionId, result, engine.version());
    }

    private record Checked(UUID orgId, OperationEntities.Operation op, Server server, EnginePort.Target pinned) {}

    /** Contrôles communes aux deux modes, juste avant d'agir (contexte RLS de l'organisation posé). */
    private Checked check(UUID executionId) {
        rls.enterPlatform();
        OperationEntities.Execution e = executions.findById(executionId).orElseThrow();
        UUID orgId = e.getOrganizationId();
        rls.enterOrg(orgId);
        OperationEntities.Operation op = operations.findById(e.getOperationId()).orElseThrow();
        Server server = servers.require(orgId, op.getServerId());
        OperationType type = op.typeEnum();
        Instant now = Instant.now();
        if (!type.autoApproved() && operationService.validApproval(op.getId(), now).isEmpty()) {
            throw new IllegalStateException("approval_expired");
        }
        if (server.getHostFingerprint() == null) {
            throw new IllegalStateException("host_key_not_trusted");
        }
        if ("BLOCKED".equals(server.getProtection())
                || ("READ_ONLY".equals(server.getProtection()) && type.risk() != OperationType.Risk.READ)) {
            throw new IllegalStateException("server_protected");
        }
        // Anti-SSRF : contrôlé sur l'adresse résolue à l'instant de l'exécution (le DNS a pu changer).
        EnginePort.Target pinned = resolver.pinned(OperationService.target(server));
        return new Checked(orgId, op, server, pinned);
    }

    private Prepared prepare(UUID executionId) {
        Checked c = check(executionId);
        UUID orgId = c.orgId();
        OperationEntities.Operation op = c.op();
        Server server = c.server();
        OperationType type = op.typeEnum();
        CredentialService.Unlocked active = credentials.unlock(orgId, server.getId(), "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("no_active_credential"));
        Optional<CredentialService.Unlocked> pending = type == OperationType.ROTATE_CREDENTIAL
                ? credentials.unlock(orgId, server.getId(), "PENDING") : Optional.empty();
        if (type == OperationType.ROTATE_CREDENTIAL && pending.isEmpty()) {
            throw new IllegalStateException("no_pending_credential");
        }
        String backup = type == OperationType.RUN_BACKUP || type == OperationType.INSTALL_BACKUPS
                ? credentials.backupPassphrase(orgId) : null;
        String approvedHash = op.getPlanHash();
        Map<String, Object> params = op.getParameters();
        EnginePort.Credentials creds = new EnginePort.Credentials(active.privatePem(), server.getHostFingerprint(), backup,
                active.publicKey(), pending.map(CredentialService.Unlocked::privatePem).orElse(null),
                pending.map(CredentialService.Unlocked::publicKey).orElse(null));
        // Copie détachée : le prédicat est évalué hors transaction, sur l'état frais du serveur.
        Server snapshotOfTarget = server;
        Map<String, String> gitKeys = Map.of();
        if (type == OperationType.DEPLOY_PLATFORM || type == OperationType.DEPLOY_APP) {
            gitKeys = gitCredentials.unlockForDeploy(orgId, params == null ? Map.of() : params);
        }
        Map<String, EnginePort.StagingPayload> stagingPayloads = unlockStaging(orgId, type, params);
        EnginePort.ExecutionRequest request = new EnginePort.ExecutionRequest(type, c.pinned(), params,
                creds, plan -> approvedHash.equals(PlanHash.of(plan, snapshotOfTarget, params)), gitKeys,
                stagingPayloads);
        audit.record(orgId, system(), AuditService.Entry.success("operation.started", "operation", op.getId(),
                Map.of("executionId", executionId.toString(), "runner", runnerId)));
        return new Prepared(request, orgId, op.getId(), server.getId(), type);
    }

    private Map<String, EnginePort.StagingPayload> unlockStaging(UUID orgId, OperationType type,
                                                                  Map<String, Object> params) {
        if (type != OperationType.RESTORE_PLATFORM && type != OperationType.SET_PLATFORM_SECRET) {
            return Map.of();
        }
        if (params == null) {
            return Map.of();
        }
        Map<String, EnginePort.StagingPayload> out = new LinkedHashMap<>();
        for (String key : List.of("sqlStagingId", "uploadsStagingId", "secretStagingId")) {
            Object raw = params.get(key);
            if (raw == null || String.valueOf(raw).isBlank()) {
                continue;
            }
            StagingService.Opened o = staging.unlock(orgId, UUID.fromString(String.valueOf(raw)));
            out.put(key, new EnginePort.StagingPayload(o.id(), o.kind(), o.originalName(), o.content(), o.meta()));
        }
        return Map.copyOf(out);
    }

    void finish(UUID executionId, EnginePort.Result result, String engineVersion) {
        int max = properties.getOperations().getOutputMaxBytes();
        String output = result.output() == null ? "" : result.output();
        String sha = Hashing.sha256Hex(output);
        String excerpt = truncate(output, max);
        tx.executeWithoutResult(s -> {
            rls.enterPlatform();
            OperationEntities.Execution e = executions.lockById(executionId).orElseThrow();
            if (!"RUNNING".equals(e.getStatus())) {
                return; // déjà terminée (runner et ménage concurrents) : un seul compte rendu fait foi
            }
            UUID orgId = e.getOrganizationId();
            rls.enterOrg(orgId);
            e.finish(result.success(), result.exitCode(), excerpt, sha, result.error(), result.verification());
            OperationEntities.Operation op = operations.findById(e.getOperationId()).orElseThrow();
            op.status(result.success() ? "SUCCEEDED" : "FAILED");
            Server server = servers.require(orgId, op.getServerId());
            EnginePort.Observation obs = result.observation();
            if (obs != null && obs.snapshot() != null) {
                servers.recordSnapshot(server, obs.snapshot(), obs.auditScore(), obs.auditGrade(), obs.osFamily(),
                        obs.osVersion(), obs.capabilities(), engineVersion == null ? engine.version() : engineVersion);
            } else if (!result.success() && result.error() != null && result.error().contains("auth")) {
                server.observed("AUTH_FAILED", null, null, null, server.getEngineVersion(), Instant.now());
            }
            if (result.success() && result.credentialRotated()) {
                credentials.promotePending(orgId, server.getId());
            }
            if (result.success()) {
                consumeStaging(orgId, op.getParameters());
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("executionId", executionId.toString());
            details.put("type", op.getType());
            details.put("outputSha256", sha);
            if (result.exitCode() != null) {
                details.put("exitCode", result.exitCode());
            }
            if (result.error() != null) {
                details.put("error", truncate(result.error(), 500));
            }
            audit.record(orgId, system(), result.success()
                    ? AuditService.Entry.success("operation.succeeded", "operation", op.getId(), details)
                    : AuditService.Entry.failure("operation.failed", "operation", op.getId(), details));
        });
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void heartbeat() {
        if (running.isEmpty()) {
            return;
        }
        List<UUID> ids = List.copyOf(running);
        tx.executeWithoutResult(s -> {
            rls.enterPlatform();
            executions.heartbeat(ids, Instant.now());
        });
    }

    /** Exécutions orphelines (runner arrêté) et propositions expirées. */
    @Scheduled(fixedDelay = 120_000, initialDelay = 30_000)
    public void housekeeping() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(Duration.ofMinutes(properties.getRunner().getStaleAfterMinutes()));
        List<UUID> stale = tx.execute(s -> {
            rls.enterPlatform();
            return executions.stale(staleBefore).stream().map(OperationEntities.Execution::getId)
                    .filter(id -> !running.contains(id)).toList();
        });
        for (UUID id : stale == null ? List.<UUID>of() : stale) {
            finish(id, new EnginePort.Result(false, null, "", "runner_lost", Map.of(), null, false), null);
        }
        tx.executeWithoutResult(s -> {
            rls.enterPlatform();
            for (OperationEntities.Operation op : operations.expiredProposals(now)) {
                op.status("EXPIRED");
                audit.record(op.getOrganizationId(), system(), AuditService.Entry.failure("operation.expired",
                        "operation", op.getId(), Map.of("reason", "proposal_expired")));
            }
            executions.purgeOutputs(now.minus(Duration.ofDays(properties.getOperations().getOutputRetentionDays())));
        });
    }

    private void consumeStaging(UUID orgId, Map<String, Object> params) {
        if (params == null) {
            return;
        }
        for (String key : List.of("sqlStagingId", "uploadsStagingId", "secretStagingId")) {
            Object raw = params.get(key);
            if (raw == null || String.valueOf(raw).isBlank()) {
                continue;
            }
            try {
                staging.markConsumed(orgId, UUID.fromString(String.valueOf(raw)));
            } catch (RuntimeException e) {
                log.debug("Staging {} déjà consommé ou invalide : {}", raw, e.getMessage());
            }
        }
    }

    static String truncate(String s, int maxBytes) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) {
            return s;
        }
        // Garde la fin (les erreurs sont en bas) ; coupe sur une frontière de caractère.
        String tail = new String(b, b.length - maxBytes, maxBytes, StandardCharsets.UTF_8);
        return "…[" + (b.length - maxBytes) + " octets tronqués]\n" + tail.substring(Math.min(1, tail.length()));
    }

    private static Actor system() {
        return new Actor(Actor.Type.RUNNER, null, null, null, null, null, Set.of(), "en");
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
