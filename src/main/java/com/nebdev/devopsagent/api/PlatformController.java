package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.journal.ActionCatalog;
import com.nebdev.devopsagent.journal.DeployActivity;
import com.nebdev.devopsagent.journal.JournalEntry;
import com.nebdev.devopsagent.journal.JournalStore;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.DeployBusyException;
import com.nebdev.devopsagent.ssh.LongJobTracker;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.AppDeployPlan;
import com.nebdev.devopsagent.stack.NotifyService;
import com.nebdev.devopsagent.stack.PlatformDeployService;
import com.nebdev.devopsagent.stack.PlatformIntentStore;
import com.nebdev.devopsagent.stack.PlatformRestoreService;
import com.nebdev.devopsagent.stack.PlatformSecretService;
import com.nebdev.devopsagent.stack.PlatformSpec;
import com.nebdev.devopsagent.stack.PlatformSqlService;
import com.nebdev.devopsagent.stack.SqlConsolePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platforms")
public class PlatformController {

    private final PlatformIntentStore store;
    private final PlatformDeployService deployService;
    private final PlatformRestoreService restoreService;
    private final PlatformSqlService sqlService;
    private final PlatformSecretService secretService;
    private final LongJobTracker longJobs;
    private final NotifyService notifyService;
    private final JournalStore journalStore;

    public PlatformController(
            PlatformIntentStore store,
            PlatformDeployService deployService,
            PlatformRestoreService restoreService,
            PlatformSqlService sqlService,
            PlatformSecretService secretService,
            LongJobTracker longJobs,
            NotifyService notifyService,
            JournalStore journalStore
    ) {
        this.store = store;
        this.deployService = deployService;
        this.restoreService = restoreService;
        this.sqlService = sqlService;
        this.secretService = secretService;
        this.longJobs = longJobs;
        this.notifyService = notifyService;
        this.journalStore = journalStore;
    }

    @GetMapping
    public Map<String, Object> list() throws IOException {
        return Map.of(
                "platforms", masked(store.load()),
                "deployReady", true,
                "note", "Déployer = CHANGE confirmé (V7-02). Enregistrer reste local."
        );
    }

    private static List<PlatformSpec> masked(List<PlatformSpec> platforms) {
        return platforms.stream().map(PlatformSpec::masked).toList();
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody(required = false) PlatformsUpdate body) throws IOException {
        List<PlatformSpec> incoming = body == null || body.platforms() == null ? List.of() : body.platforms();
        String vpsIp = body == null ? null : body.vpsIp();
        List<PlatformSpec> saved = store.save(incoming, vpsIp);
        return Map.of(
                "platforms", masked(saved),
                "deployReady", true,
                "note", "Enregistré sur ce poste. Déployer demande une confirmation CHANGE."
        );
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam("id") String id) throws IOException {
        List<PlatformSpec> saved = store.delete(id);
        return Map.of(
                "platforms", masked(saved),
                "removed", id.trim().toLowerCase(),
                "note", "Ligne retirée de la liste locale. Rien n’a été arrêté sur un VPS."
        );
    }

    @GetMapping("/deploy-plan")
    public Map<String, Object> deployPlan(
            @RequestParam("id") String id,
            @RequestParam(value = "service", required = false) String service
    ) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Indique l’id de plateforme (paramètre id).");
        }
        AppDeployPlan plan = deployService.plan(id.trim(), service);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", plan.id());
        out.put("service", service == null || service.isBlank() ? "" : service.trim().toLowerCase());
        out.put("actions", plan.actions());
        out.put("warnings", plan.warnings());
        out.put("canProceed", plan.canProceed());
        out.put("blocker", plan.blocker() == null ? "" : plan.blocker());
        return out;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam("id") String id) throws IOException {
        List<PlatformDeployService.ServiceStatus> services = deployService.status(id.trim());
        return Map.of(
                "id", id.trim().toLowerCase(),
                "services", services,
                "online", !services.isEmpty() && services.stream().allMatch(PlatformDeployService.ServiceStatus::online)
        );
    }

    /**
     * Déploiement asynchrone : répond tout de suite avec un {@code jobId}.
     * Le navigateur suit via {@code GET /jobs/{jobId}} (évite « Failed to fetch » sur builds longs).
     */
    @PostMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deploy(@RequestBody DeployRequest body) {
        if (body == null || body.id() == null || body.id().isBlank()) {
            throw new IllegalArgumentException("Indique la plateforme à déployer.");
        }
        if (body.host() == null || body.host().isBlank() || body.user() == null || body.user().isBlank()) {
            throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH.");
        }
        int port = body.port() == null ? 22 : body.port();
        String platformId = body.id().trim();
        String serviceId = body.service() == null || body.service().isBlank()
                ? null
                : body.service().trim().toLowerCase();
        SshTarget target = new SshTarget(body.host().trim(), port, body.user().trim());
        boolean confirmed = Boolean.TRUE.equals(body.confirm());
        Instant started = Instant.now();
        String jobLabel = serviceId == null ? platformId : platformId + "/" + serviceId;
        String jobId = longJobs.start("deploy-platform", jobLabel, target.host(), () -> {
            try {
                PlatformDeployService.DeployOutcome outcome =
                        deployService.deploy(platformId, target, confirmed, serviceId);
                Map<String, String> activity = recordDeployDone(platformId, target, started, true, null, outcome.rawLog());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", "ok");
                payload.put("id", platformId);
                if (serviceId != null) {
                    payload.put("service", serviceId);
                }
                payload.put("risk", "CHANGE");
                payload.put("snapshot", outcome.discovery().snapshot());
                payload.put("report", outcome.discovery().report());
                payload.put("apps", activity.getOrDefault("apps", ""));
                payload.put("summary", activity.getOrDefault("summary", ""));
                String mailBody = activity.getOrDefault("summary", "Plateforme " + platformId + " déployée")
                        + "\n\nServeur : " + target.user() + "@" + target.host()
                        + "\nDate (UTC) : " + Instant.now()
                        + (serviceId != null ? "\nService : " + serviceId : "")
                        + (activity.containsKey("services") ? "\n\nDétail :\n" + activity.get("services").replace(" | ", "\n") : "")
                        + (activity.containsKey("urls") ? "\n\nURLs :\n" + activity.get("urls").replace(" · ", "\n") : "")
                        + "\n\nVérifie les URL publiques depuis le cockpit si besoin.";
                notifyService.tryAnnounce(target, "deploy-ok",
                        serviceId == null
                                ? "Plateforme " + platformId + " déployée"
                                : "Service " + serviceId + " (" + platformId + ") déployé",
                        mailBody);
                return payload;
            } catch (Exception e) {
                String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                recordDeployDone(platformId, target, started, false, err, null);
                notifyService.tryAnnounce(target, "deploy-fail",
                        "Échec déploiement " + jobLabel,
                        "Le déploiement de « " + jobLabel + " » a échoué sur "
                                + target.user() + "@" + target.host() + ".\n\n"
                                + err + "\n\nOuvre le journal Activité dans le cockpit.");
                throw e;
            }
        });
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("status", "accepted");
        accepted.put("jobId", jobId);
        accepted.put("id", platformId);
        if (serviceId != null) {
            accepted.put("service", serviceId);
        }
        accepted.put("risk", "CHANGE");
        accepted.put("message", "Déploiement lancé sur le serveur. Suivi en cours — ne ferme pas le cockpit.");
        return ResponseEntity.accepted().body(accepted);
    }

    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> job(@PathVariable("jobId") String jobId) {
        return longJobs.snapshot(jobId);
    }

    private Map<String, String> recordDeployDone(String platformId, SshTarget target, Instant started,
                                                   boolean ok, String error, String rawLog) {
        Map<String, String> details = new LinkedHashMap<>();
        try {
            long ms = Math.max(0, Instant.now().toEpochMilli() - started.toEpochMilli());
            try {
                details.putAll(DeployActivity.details(store.require(platformId), rawLog, ok));
            } catch (Exception ignored) {
                details.put("id", platformId);
                details.put("summary", ok
                        ? "Plateforme « " + platformId + " » déployée"
                        : "Échec du déploiement de « " + platformId + " »");
            }
            journalStore.append(new JournalEntry(
                    Instant.now().toString(),
                    System.getProperty("user.name", ""),
                    target.host(),
                    target.user(),
                    "POST",
                    "/api/v1/platforms/deploy",
                    "Déploiement d’une plateforme (terminé)",
                    ActionCatalog.Risk.CHANGE.name(),
                    ok ? JournalEntry.OK : JournalEntry.FAILED,
                    ok ? 200 : 500,
                    ms,
                    details,
                    error == null ? "" : error
            ));
        } catch (IOException ignored) {
            // journal best-effort
        }
        return details;
    }

    @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> restore(
            @RequestParam("id") String id,
            @RequestParam("host") String host,
            @RequestParam(value = "port", required = false) Integer port,
            @RequestParam("user") String user,
            @RequestParam(value = "confirm", required = false) Boolean confirm,
            @RequestParam(value = "uploadsSubdir", required = false) String uploadsSubdir,
            @RequestParam(value = "foreignKeyChecks", required = false) Boolean foreignKeyChecks,
            @RequestPart(value = "sql", required = false) MultipartFile sql,
            @RequestPart(value = "uploads", required = false) MultipartFile uploads
    ) throws IOException {
        if (host == null || host.isBlank() || user == null || user.isBlank()) {
            throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH.");
        }
        List<Path> temps = new ArrayList<>();
        try {
            PlatformRestoreService.RestoreFile sqlFile = toTemp(sql, temps);
            PlatformRestoreService.RestoreFile uploadsFile = toTemp(uploads, temps);
            PlatformRestoreService.RestoreResult result = restoreService.restore(
                    id.trim(),
                    new SshTarget(host.trim(), port == null ? 22 : port, user.trim()),
                    Boolean.TRUE.equals(confirm),
                    sqlFile,
                    uploadsFile,
                    uploadsSubdir,
                    !Boolean.FALSE.equals(foreignKeyChecks)
            );
            return Map.of(
                    "status", "ok",
                    "id", result.platformId(),
                    "risk", "CHANGE",
                    "report", result.report()
            );
        } finally {
            for (Path p : temps) {
                Files.deleteIfExists(p);
            }
        }
    }

    @PostMapping("/sql/analyze")
    public Map<String, Object> analyzeSql(@RequestBody SqlRequest body) {
        SqlConsolePolicy.Analysis a = sqlService.analyze(body == null ? null : body.sql());
        return Map.of(
                "kind", a.kind(),
                "risk", a.kind() == SqlConsolePolicy.Kind.READ ? "READ" : "CHANGE",
                "statements", a.statements().stream().map(s -> Map.of("keyword", s.keyword(), "sql", s.sql())).toList(),
                "warnings", a.warnings()
        );
    }

    @PostMapping("/sql")
    public PlatformSqlService.SqlResult runSql(@RequestBody SqlRequest body) throws IOException {
        if (body == null || body.id() == null || body.id().isBlank()) {
            throw new IllegalArgumentException("Indique la plateforme.");
        }
        if (body.host() == null || body.host().isBlank() || body.user() == null || body.user().isBlank()) {
            throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH.");
        }
        return sqlService.run(
                body.id().trim(),
                new SshTarget(body.host().trim(), body.port() == null ? 22 : body.port(), body.user().trim()),
                body.sql(),
                Boolean.TRUE.equals(body.confirm())
        );
    }

    @GetMapping("/secrets")
    public Map<String, Object> secrets(
            @RequestParam("id") String id,
            @RequestParam("host") String host,
            @RequestParam(value = "port", required = false) Integer port,
            @RequestParam("user") String user
    ) throws IOException {
        Map<String, List<String>> names = secretService.list(id.trim(), target(host, port, user));
        return Map.of("id", id.trim(), "names", names);
    }

    @PostMapping("/secrets")
    public Map<String, Object> setSecret(@RequestBody SecretRequest body) throws IOException {
        requireSecretBody(body);
        PlatformSecretService.Outcome o = secretService.set(body.id().trim(), body.service(), body.name(), body.value(),
                !Boolean.FALSE.equals(body.restart()), target(body.host(), body.port(), body.user()),
                Boolean.TRUE.equals(body.confirm()));
        return Map.of("status", "ok", "risk", "CHANGE", "outcome", o);
    }

    @PostMapping("/secrets/remove")
    public Map<String, Object> removeSecret(@RequestBody SecretRequest body) throws IOException {
        requireSecretBody(body);
        PlatformSecretService.Outcome o = secretService.unset(body.id().trim(), body.service(), body.name(),
                !Boolean.FALSE.equals(body.restart()), target(body.host(), body.port(), body.user()),
                Boolean.TRUE.equals(body.confirm()));
        return Map.of("status", "ok", "risk", "CHANGE", "outcome", o);
    }

    private static void requireSecretBody(SecretRequest body) {
        if (body == null || body.id() == null || body.id().isBlank()) {
            throw new IllegalArgumentException("Indique la plateforme.");
        }
    }

    private static SshTarget target(String host, Integer port, String user) {
        if (host == null || host.isBlank() || user == null || user.isBlank()) {
            throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH.");
        }
        return new SshTarget(host.trim(), port == null ? 22 : port, user.trim());
    }

    private static PlatformRestoreService.RestoreFile toTemp(MultipartFile part, List<Path> temps) throws IOException {
        if (part == null || part.isEmpty()) {
            return null;
        }
        Path tmp = Files.createTempFile("autopilot-restore-", ".bin");
        temps.add(tmp);
        part.transferTo(tmp);
        return new PlatformRestoreService.RestoreFile(tmp, part.getOriginalFilename());
    }

    @ExceptionHandler(DeployBusyException.class)
    public ResponseEntity<Map<String, Object>> busy(DeployBusyException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("code", "DEPLOY_BUSY");
        body.put("active", ex.holder() == null ? Map.of() : ex.holder().toMap());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(UnsafeActionException.class)
    public ResponseEntity<Map<String, String>> forbidden(UnsafeActionException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> io(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    public record PlatformsUpdate(List<PlatformSpec> platforms, String vpsIp) {
        public PlatformsUpdate {
            platforms = platforms == null ? List.of() : new ArrayList<>(platforms);
        }
    }

    /** {@code service} optionnel : redeploy d’un seul service (V7-07). */
    public record DeployRequest(String id, String host, Integer port, String user, Boolean confirm, String service) {
        public DeployRequest(String id, String host, Integer port, String user, Boolean confirm) {
            this(id, host, port, user, confirm, null);
        }
    }

    public record SqlRequest(String id, String host, Integer port, String user, String sql, Boolean confirm) {}

    /** {@code value} n’est jamais renvoyé, journalisé ni enregistré sur ce poste. */
    public record SecretRequest(String id, String service, String name, String value, Boolean restart,
                                String host, Integer port, String user, Boolean confirm) {
        @Override
        public String toString() {
            return "SecretRequest[id=" + id + ", service=" + service + ", name=" + name + ", value=***]";
        }
    }
}
