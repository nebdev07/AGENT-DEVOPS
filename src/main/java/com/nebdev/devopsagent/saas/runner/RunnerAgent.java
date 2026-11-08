package com.nebdev.devopsagent.saas.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.saas.operation.EnginePort;
import com.nebdev.devopsagent.saas.operation.PlanHash;
import com.nebdev.devopsagent.saas.operation.RunnerContract;
import com.nebdev.devopsagent.saas.shared.SealedBox;
import com.nebdev.devopsagent.saas.shared.TargetPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runner séparé (ADR-055) : seul détenteur de la clé privée qui ouvre les secrets scellés. Il tire les exécutions
 * du control plane, ouvre les clés SSH le temps de l'exécution, laisse le moteur recalculer le plan sur l'état frais
 * du serveur et n'agit que si le hash correspond exactement au plan approuvé. Pas de base, pas de port d'écoute.
 *
 * <p>Ce que le runner ne fait jamais : choisir une opération, contourner une approbation, réessayer un CHANGE.
 */
public class RunnerAgent implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunnerAgent.class);

    private final RunnerAgentProperties props;
    private final EnginePort engine;
    private final ObjectMapper mapper;
    private final PrivateKey key;
    private final String runnerId;
    private final URI base;
    private final HttpClient http;
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService workers;
    private ScheduledExecutorService heartbeats;

    public RunnerAgent(RunnerAgentProperties props, EnginePort engine, ObjectMapper mapper) {
        this.props = props;
        this.engine = engine;
        this.mapper = mapper;
        this.key = loadKey(props);
        this.base = controlPlane(props);
        if (props.getToken() == null || props.getToken().length() < 32) {
            throw new IllegalStateException("AUTOPILOT_RUNNER_TOKEN doit faire au moins 32 caractères.");
        }
        this.runnerId = props.getId() == null || props.getId().isBlank()
                ? hostname() + "-" + UUID.randomUUID().toString().substring(0, 8) : props.getId().strip();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        heartbeats = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "autopilot-runner-heartbeat"));
        log.info("Runner {} prêt : control plane {}, clé {}", runnerId, base, SealedBox.keyId(SealedBox.publicOf(key)));
    }

    public String runnerId() {
        return runnerId;
    }

    // ───────────── Cycle de vie ─────────────

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        int n = Math.max(1, props.getThreads());
        // Fils non démons : sans serveur web, ce sont eux qui gardent le processus en vie.
        workers = Executors.newFixedThreadPool(n, r -> new Thread(r, "autopilot-runner"));
        for (int i = 0; i < n; i++) {
            workers.submit(this::loop);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (workers != null) {
            workers.shutdownNow();
        }
        heartbeats.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        long backoff = props.getPollMillis();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                boolean worked = pollOnce();
                backoff = props.getPollMillis();
                if (!worked) {
                    Thread.sleep(props.getPollMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException | IOException e) {
                // Control plane indisponible : on attend, sans jamais rejouer une exécution.
                log.warn("Control plane injoignable ou réponse refusée : {}", e.getMessage());
                backoff = Math.min(backoff * 2, 60_000);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Une réservation et, s'il y a lieu, son exécution complète. {@code false} : rien à faire. */
    public boolean pollOnce() throws IOException, InterruptedException {
        Optional<RunnerContract.Job> job = claim();
        if (job.isEmpty()) {
            return false;
        }
        RunnerContract.Job j = job.get();
        ScheduledFuture<?> beat = heartbeats.scheduleAtFixedRate(() -> heartbeat(j.executionId()),
                props.getHeartbeatMillis(), props.getHeartbeatMillis(), TimeUnit.MILLISECONDS);
        RunnerContract.JobResult result;
        try {
            result = execute(j);
        } finally {
            beat.cancel(false);
        }
        report(j.executionId(), result);
        return true;
    }

    // ───────────── Exécution ─────────────

    RunnerContract.JobResult execute(RunnerContract.Job job) {
        EnginePort.Credentials creds;
        try {
            creds = unseal(job);
        } catch (RuntimeException e) {
            log.warn("Exécution {} : ouverture des secrets refusée ({})", job.executionId(), e.getMessage());
            return failure("unseal_failed");
        }
        EnginePort.Target pinned;
        try {
            // Anti-SSRF : contrôle et résolution par le runner lui-même (son réseau, sa politique), IP figée.
            String ip = TargetPolicy.resolveForConnection(job.target().host(), props.isAllowPrivateTargets(),
                    TargetPolicy.normalize(props.getDeniedTargets())).getHostAddress();
            pinned = new EnginePort.Target(ip, job.target().port(), job.target().user(), job.target().name());
        } catch (TargetPolicy.Refused e) {
            log.warn("Exécution {} : cible refusée ({})", job.executionId(), e.getMessage());
            return failure(e.code());
        }
        Map<String, Object> params = job.parameters() == null ? Map.of() : job.parameters();
        String approved = job.approvedPlanHash();
        PlanHash.HashTarget target = job.hashTarget();
        Map<String, String> gitKeys = unsealGitKeys(job);
        Map<String, EnginePort.StagingPayload> stagingPayloads = unsealStaging(job);
        EnginePort.ExecutionRequest request = new EnginePort.ExecutionRequest(job.type(), pinned, params, creds,
                plan -> approved != null && approved.equals(PlanHash.of(plan, target, params)), gitKeys,
                stagingPayloads);
        try {
            EnginePort.Result r = engine.execute(request);
            return new RunnerContract.JobResult(r.success(), r.exitCode(), r.output(), r.error(), r.verification(),
                    r.observation(), r.credentialRotated(), engine.version());
        } catch (RuntimeException e) {
            log.error("Moteur : exception non gérée pour {}", job.executionId(), e);
            return failure("engine_error: " + e.getClass().getSimpleName());
        }
    }

    private EnginePort.Credentials unseal(RunnerContract.Job job) {
        UUID org = job.organizationId();
        if (job.active() == null) {
            throw new IllegalStateException("no_active_credential");
        }
        String active = open(SealedBox.credentialAad(org, job.active().credentialId()), job.active().sealed());
        String pending = job.pending() == null ? null
                : open(SealedBox.credentialAad(org, job.pending().credentialId()), job.pending().sealed());
        String backup = job.backupPassphraseSealed() == null ? null
                : open(SealedBox.secretAad(org, "backup.passphrase"), job.backupPassphraseSealed());
        String fingerprint = job.hashTarget() == null ? null : job.hashTarget().hostFingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalStateException("host_key_not_trusted");
        }
        return new EnginePort.Credentials(active, fingerprint, backup, job.active().publicKey(), pending,
                job.pending() == null ? null : job.pending().publicKey());
    }

    private Map<String, String> unsealGitKeys(RunnerContract.Job job) {
        if (job.gitKeys() == null || job.gitKeys().isEmpty()) {
            return Map.of();
        }
        UUID org = job.organizationId();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, RunnerContract.SealedValue> e : job.gitKeys().entrySet()) {
            String pem = open(SealedBox.gitAad(org, e.getValue().credentialId()), e.getValue().sealed());
            out.put(e.getKey(), Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8)));
        }
        return Map.copyOf(out);
    }

    private Map<String, EnginePort.StagingPayload> unsealStaging(RunnerContract.Job job) {
        if (job.staging() == null || job.staging().isEmpty()) {
            return Map.of();
        }
        UUID org = job.organizationId();
        Map<String, EnginePort.StagingPayload> out = new LinkedHashMap<>();
        for (Map.Entry<String, RunnerContract.SealedStaging> e : job.staging().entrySet()) {
            RunnerContract.SealedStaging s = e.getValue();
            byte[] content = SealedBox.open(key, SealedBox.stagingAad(org, s.id()),
                    Base64.getDecoder().decode(s.sealed()));
            out.put(e.getKey(), new EnginePort.StagingPayload(s.id(), s.kind(), s.originalName(), content, Map.of()));
        }
        return Map.copyOf(out);
    }

    private String open(String aad, String sealedBase64) {
        return new String(SealedBox.open(key, aad, Base64.getDecoder().decode(sealedBase64)), StandardCharsets.UTF_8);
    }

    private RunnerContract.JobResult failure(String error) {
        return new RunnerContract.JobResult(false, null, "", error, Map.of(), null, false, engine.version());
    }

    // ───────────── HTTP vers le control plane ─────────────

    private Optional<RunnerContract.Job> claim() throws IOException, InterruptedException {
        HttpResponse<String> r = send("/claim", "{}");
        if (r.statusCode() == 204) {
            return Optional.empty();
        }
        if (r.statusCode() != 200) {
            throw new IOException("claim : HTTP " + r.statusCode());
        }
        return Optional.of(mapper.readValue(r.body(), RunnerContract.Job.class));
    }

    private void heartbeat(UUID executionId) {
        try {
            HttpResponse<String> r = send("/executions/" + executionId + "/heartbeat", "{}");
            if (r.statusCode() != 204) {
                log.warn("Battement refusé pour {} : HTTP {}", executionId, r.statusCode());
            }
        } catch (IOException e) {
            log.warn("Battement impossible pour {} : {}", executionId, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Le compte rendu est réessayé (le résultat d'un CHANGE ne doit pas se perdre) ; l'exécution, jamais. */
    private void report(UUID executionId, RunnerContract.JobResult result) throws InterruptedException {
        String body;
        try {
            body = mapper.writeValueAsString(result);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        long wait = 1000;
        for (int attempt = 1; attempt <= 8; attempt++) {
            try {
                HttpResponse<String> r = send("/executions/" + executionId + "/result", body);
                if (r.statusCode() == 204) {
                    return;
                }
                if (r.statusCode() == 409 || r.statusCode() == 404) {
                    log.warn("Compte rendu de {} refusé (HTTP {}) : exécution déjà close côté control plane.",
                            executionId, r.statusCode());
                    return;
                }
                log.warn("Compte rendu de {} : HTTP {} (tentative {})", executionId, r.statusCode(), attempt);
            } catch (IOException e) {
                log.warn("Compte rendu de {} impossible (tentative {}) : {}", executionId, attempt, e.getMessage());
            }
            Thread.sleep(wait);
            wait = Math.min(wait * 2, 30_000);
        }
        log.error("Compte rendu de {} perdu : le control plane la clora en « runner_lost ».", executionId);
    }

    private HttpResponse<String> send(String path, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(base.resolve("api/v2/internal/runner" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + props.getToken())
                .header(RunnerContract.RUNNER_ID_HEADER, runnerId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ───────────── Démarrage ─────────────

    private static PrivateKey loadKey(RunnerAgentProperties props) {
        String pem = props.getPrivateKey();
        if ((pem == null || pem.isBlank()) && props.getPrivateKeyFile() != null && !props.getPrivateKeyFile().isBlank()) {
            try {
                pem = Files.readString(Path.of(props.getPrivateKeyFile()));
            } catch (IOException e) {
                throw new IllegalStateException("Clé privée du runner illisible : " + props.getPrivateKeyFile());
            }
        }
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("AUTOPILOT_RUNNER_PRIVATE_KEY(_FILE) manquante : le runner ne peut rien ouvrir.");
        }
        return SealedBox.parsePrivateKey(pem);
    }

    static URI controlPlane(RunnerAgentProperties props) {
        String url = props.getControlPlaneUrl() == null ? "" : props.getControlPlaneUrl().strip();
        if (url.isEmpty()) {
            throw new IllegalStateException("AUTOPILOT_CONTROL_PLANE_URL manquante.");
        }
        URI uri = URI.create(url.endsWith("/") ? url : url + "/");
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
        if ("http".equalsIgnoreCase(uri.getScheme()) && !local && !props.isAllowInsecureHttp()) {
            throw new IllegalStateException("Control plane en http:// hors boucle locale : le jeton circulerait en clair "
                    + "(utiliser https, ou AUTOPILOT_AGENT_ALLOW_INSECURE_HTTP=true en connaissance de cause).");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("URL du control plane invalide : " + url);
        }
        return uri;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9._-]", "");
        } catch (Exception e) {
            return "runner";
        }
    }
}
