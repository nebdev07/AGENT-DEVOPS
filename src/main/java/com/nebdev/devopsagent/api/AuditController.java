package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.audit.AuditHtmlRenderer;
import com.nebdev.devopsagent.audit.AuditReport;
import com.nebdev.devopsagent.audit.AuditRules;
import com.nebdev.devopsagent.discovery.model.LiveSnapshot;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import com.nebdev.devopsagent.state.StateStore;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Rapport d’audit (READ) calculé sur la dernière analyse SSH du serveur ; aucune connexion au serveur. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final StateStore stateStore;

    public AuditController(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    @GetMapping
    public AuditReport audit(@RequestParam(value = "host", required = false) String host) throws IOException {
        return AuditRules.evaluate(snapshot(host));
    }

    @GetMapping("/report")
    public ResponseEntity<byte[]> report(
            @RequestParam(value = "host", required = false) String host,
            @RequestParam(value = "client", required = false) String client
    ) throws IOException {
        AuditReport report = AuditRules.evaluate(snapshot(host));
        String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(report.capturedAt());
        String file = "audit-" + report.host().replaceAll("[^A-Za-z0-9.-]", "_") + "-" + date + ".html";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(AuditHtmlRenderer.render(report, client).getBytes(StandardCharsets.UTF_8));
    }

    private ServerSnapshot snapshot(String host) throws IOException {
        StateStore.InfrastructureState state = host == null || host.isBlank()
                ? stateStore.loadLatest()
                : stateStore.load(host.trim());
        ServerSnapshot snap = state == null ? null : state.lastDiscovery();
        if (!LiveSnapshot.isLive(snap)) {
            throw new IllegalStateException("Aucune analyse SSH réelle pour ce serveur. Clique « Analyser » d’abord.");
        }
        return snap;
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
