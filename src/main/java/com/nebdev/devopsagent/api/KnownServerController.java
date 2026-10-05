package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.discovery.model.LiveSnapshot;
import com.nebdev.devopsagent.report.DiscoveryReportFormatter;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.state.KnownServerStore;
import com.nebdev.devopsagent.state.KnownServerStore.KnownServer;
import com.nebdev.devopsagent.state.StateStore;
import com.nebdev.devopsagent.state.StateStore.InfrastructureState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serveurs déjà analysés : rouvrir une session (paramètres + dernière analyse) sans reconnexion. */
@RestController
@RequestMapping("/api/v1/servers")
public class KnownServerController {

    private final KnownServerStore store;
    private final StateStore stateStore;
    private final DiscoveryReportFormatter reportFormatter;
    private final ForbiddenTargetGuard guard;

    public KnownServerController(
            KnownServerStore store,
            StateStore stateStore,
            DiscoveryReportFormatter reportFormatter,
            ForbiddenTargetGuard guard
    ) {
        this.store = store;
        this.stateStore = stateStore;
        this.reportFormatter = reportFormatter;
        this.guard = guard;
    }

    @GetMapping
    public List<Map<String, Object>> list() throws IOException {
        return store.list().stream().map(this::describe).toList();
    }

    @GetMapping("/last")
    public ResponseEntity<Map<String, Object>> last(@RequestParam String host) throws IOException {
        InfrastructureState state = stateStore.load(host);
        if (state == null || !LiveSnapshot.isLive(state.lastDiscovery())) {
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "ssh");
        body.put("snapshot", state.lastDiscovery());
        body.put("report", reportFormatter.format(state.lastDiscovery()));
        return ResponseEntity.ok(body);
    }

    @DeleteMapping
    public Map<String, Object> forget(@RequestParam String host, @RequestParam(defaultValue = "22") int port)
            throws IOException {
        return Map.of("removed", store.forget(host, port));
    }

    private Map<String, Object> describe(KnownServer s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("host", s.host());
        m.put("port", s.port());
        m.put("user", s.user());
        m.put("os", s.os());
        m.put("lastAnalysedAt", s.lastAnalysedAt() == null ? "" : s.lastAnalysedAt().toString());
        m.put("readOnly", guard.isReadOnly(s.host()));
        return m;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> io(IOException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", ex.getMessage()));
    }
}
