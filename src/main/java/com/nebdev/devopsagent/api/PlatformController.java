package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.AppDeployPlan;
import com.nebdev.devopsagent.stack.PlatformDeployService;
import com.nebdev.devopsagent.stack.PlatformIntentStore;
import com.nebdev.devopsagent.stack.PlatformSpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platforms")
public class PlatformController {

    private final PlatformIntentStore store;
    private final PlatformDeployService deployService;

    public PlatformController(PlatformIntentStore store, PlatformDeployService deployService) {
        this.store = store;
        this.deployService = deployService;
    }

    @GetMapping
    public Map<String, Object> list() throws IOException {
        return Map.of(
                "platforms", store.load(),
                "deployReady", true,
                "note", "Déployer = CHANGE confirmé (V7-02). Enregistrer reste local."
        );
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody(required = false) PlatformsUpdate body) throws IOException {
        List<PlatformSpec> incoming = body == null || body.platforms() == null ? List.of() : body.platforms();
        String vpsIp = body == null ? null : body.vpsIp();
        List<PlatformSpec> saved = store.save(incoming, vpsIp);
        return Map.of(
                "platforms", saved,
                "deployReady", true,
                "note", "Enregistré sur ce poste. Déployer demande une confirmation CHANGE."
        );
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam("id") String id) throws IOException {
        List<PlatformSpec> saved = store.delete(id);
        return Map.of(
                "platforms", saved,
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
}
