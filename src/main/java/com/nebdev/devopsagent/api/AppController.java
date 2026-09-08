package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.AppDeployService;
import com.nebdev.devopsagent.stack.AppIntentStore;
import com.nebdev.devopsagent.stack.AppSpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/apps")
public class AppController {

    private final AppIntentStore appIntentStore;
    private final AppDeployService appDeployService;

    public AppController(AppIntentStore appIntentStore, AppDeployService appDeployService) {
        this.appIntentStore = appIntentStore;
        this.appDeployService = appDeployService;
    }

    @GetMapping
    public Map<String, Object> list() throws IOException {
        return Map.of("apps", appIntentStore.load());
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody(required = false) AppsUpdate body) throws IOException {
        List<AppSpec> incoming = body == null || body.apps() == null ? List.of() : body.apps();
        List<AppSpec> saved = appIntentStore.save(incoming);
        return Map.of("apps", saved);
    }

    @PostMapping("/deploy")
    public Map<String, Object> deploy(@RequestBody DeployRequest body) throws IOException {
        if (body == null || body.id() == null || body.id().isBlank()) {
            throw new IllegalArgumentException("Indique l’app à déployer.");
        }
        if (body.host() == null || body.host().isBlank() || body.user() == null || body.user().isBlank()) {
            throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH.");
        }
        int port = body.port() == null ? 22 : body.port();
        DiscoveryService.DiscoveryResult result = appDeployService.deploy(
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
    public ResponseEntity<Map<String, String>> remote(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    public record AppsUpdate(List<AppSpec> apps) {
        public AppsUpdate {
            apps = apps == null ? List.of() : new ArrayList<>(apps);
        }
    }

    public record DeployRequest(String id, String host, Integer port, String user, Boolean confirm) {}
}
