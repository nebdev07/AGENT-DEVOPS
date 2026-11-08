package com.nebdev.devopsagent.saas.server;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/orgs/{orgId}/servers")
@Tag(name = "client")
public class ServerController {

    private final ServerService servers;
    public ServerController(ServerService servers) {
        this.servers = servers;
    }

    public record CreateServer(@NotBlank String name, @NotBlank String host, @Min(1) @Max(65535) Integer port,
                               @NotBlank String user) {}

    public record UpdateServer(String name, String host, Integer port, String user, String protection) {}

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID orgId) {
        return servers.list(orgId).stream().map(ServerService::view).toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable UUID orgId, @Valid @RequestBody CreateServer r) {
        Server s = servers.create(orgId, r.name(), r.host(), r.port() == null ? 22 : r.port(), r.user());
        return ResponseEntity.status(HttpStatus.CREATED).body(ServerService.view(s));
    }

    @GetMapping("/{serverId}")
    public Map<String, Object> get(@PathVariable UUID orgId, @PathVariable UUID serverId) {
        return ServerService.view(servers.get(orgId, serverId));
    }

    @PatchMapping("/{serverId}")
    public Map<String, Object> update(@PathVariable UUID orgId, @PathVariable UUID serverId, @RequestBody UpdateServer r) {
        return ServerService.view(servers.update(orgId, serverId, r.name(), r.host(), r.port(), r.user(), r.protection()));
    }

    @DeleteMapping("/{serverId}")
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID serverId) {
        servers.delete(orgId, serverId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{serverId}/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot(@PathVariable UUID orgId, @PathVariable UUID serverId) {
        return servers.latestSnapshot(orgId, serverId).map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("takenAt", s.getTakenAt());
            m.put("auditScore", s.getAuditScore());
            m.put("auditGrade", s.getAuditGrade());
            m.put("payload", s.getPayload());
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.noContent().build());
    }

}
