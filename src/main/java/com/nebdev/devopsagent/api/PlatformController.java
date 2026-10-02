package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.AppDeployPlan;
import com.nebdev.devopsagent.stack.PlatformDeployService;
import com.nebdev.devopsagent.stack.PlatformIntentStore;
import com.nebdev.devopsagent.stack.PlatformRestoreService;
import com.nebdev.devopsagent.stack.PlatformSpec;
import com.nebdev.devopsagent.stack.PlatformSqlService;
import com.nebdev.devopsagent.stack.SqlConsolePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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

    public PlatformController(
            PlatformIntentStore store,
            PlatformDeployService deployService,
            PlatformRestoreService restoreService,
            PlatformSqlService sqlService
    ) {
        this.store = store;
        this.deployService = deployService;
        this.restoreService = restoreService;
        this.sqlService = sqlService;
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
    public Map<String, Object> deployPlan(@RequestParam("id") String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Indique l’id de plateforme (paramètre id).");
        }
        AppDeployPlan plan = deployService.plan(id.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", plan.id());
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

    @PostMapping("/deploy")
    public Map<String, Object> deploy(@RequestBody DeployRequest body) throws IOException {
        if (body == null || body.id() == null || body.id().isBlank()) {
            throw new IllegalArgumentException("Indique la plateforme à déployer.");
        }
        if (body.host() == null || body.host().isBlank() || body.user() == null || body.user().isBlank()) {
            throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH.");
        }
        int port = body.port() == null ? 22 : body.port();
        DiscoveryService.DiscoveryResult result = deployService.deploy(
                body.id().trim(),
                new SshTarget(body.host().trim(), port, body.user().trim()),
                Boolean.TRUE.equals(body.confirm())
        );
        return Map.of(
                "status", "ok",
                "id", body.id().trim(),
                "risk", "CHANGE",
                "snapshot", result.snapshot(),
                "report", result.report()
        );
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

    private static PlatformRestoreService.RestoreFile toTemp(MultipartFile part, List<Path> temps) throws IOException {
        if (part == null || part.isEmpty()) {
            return null;
        }
        Path tmp = Files.createTempFile("autopilot-restore-", ".bin");
        temps.add(tmp);
        part.transferTo(tmp);
        return new PlatformRestoreService.RestoreFile(tmp, part.getOriginalFilename());
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

    public record DeployRequest(String id, String host, Integer port, String user, Boolean confirm) {}

    public record SqlRequest(String id, String host, Integer port, String user, String sql, Boolean confirm) {}
}
