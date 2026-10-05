package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.InstallPlan;
import com.nebdev.devopsagent.stack.InstallService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class InstallController {

    private final InstallService installService;

    public InstallController(InstallService installService) {
        this.installService = installService;
    }

    @GetMapping("/plan")
    public InstallPlan plan(@RequestParam(name = "host", required = false) String host) throws IOException {
        return installService.plan(host);
    }

    @PostMapping("/install")
    public Map<String, Object> install(@RequestBody InstallRequest body) throws IOException {
        if (body == null || body.component() == null || body.component().isBlank()) {
            throw new IllegalArgumentException("Indiquez le composant (security, docker, …).");
        }
        if (body.host() == null || body.host().isBlank() || body.user() == null || body.user().isBlank()) {
            throw new IllegalArgumentException("Indiquez l’hôte et l’utilisateur SSH.");
        }
        int port = body.port() == null ? 22 : body.port();
        InstallService.ApplyResult applied = installService.apply(
                body.component().trim(),
                new SshTarget(body.host().trim(), port, body.user().trim()),
                Boolean.TRUE.equals(body.confirm())
        );
        DiscoveryService.DiscoveryResult result = applied.discovery();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", "ok");
        out.put("component", body.component().trim());
        out.put("risk", "CHANGE");
        out.put("snapshot", result.snapshot());
        out.put("report", result.report());
        if (applied.extras() != null) {
            out.putAll(applied.extras());
        }
        return out;
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

    public record InstallRequest(String component, String host, Integer port, String user, Boolean confirm) {}
}
