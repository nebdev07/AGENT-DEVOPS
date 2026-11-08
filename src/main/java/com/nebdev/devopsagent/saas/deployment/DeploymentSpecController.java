package com.nebdev.devopsagent.saas.deployment;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/orgs/{orgId}/servers/{serverId}/deployments")
@Tag(name = "client")
public class DeploymentSpecController {

    private final DeploymentSpecService deployments;

    public DeploymentSpecController(DeploymentSpecService deployments) {
        this.deployments = deployments;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID orgId, @PathVariable UUID serverId) {
        return deployments.list(orgId, serverId).stream()
                .map(s -> deployments.view(s, true))
                .toList();
    }

    @GetMapping("/{specId}")
    public Map<String, Object> get(@PathVariable UUID orgId, @PathVariable UUID serverId,
                                   @PathVariable UUID specId) {
        DeploymentSpec s = deployments.get(orgId, specId);
        if (!s.getServerId().equals(serverId)) {
            throw com.nebdev.devopsagent.saas.shared.ApiException.notFound("deployment_spec");
        }
        return deployments.view(s, true);
    }

    @PostMapping("/platforms")
    public ResponseEntity<Map<String, Object>> createPlatform(@PathVariable UUID orgId,
                                                              @PathVariable UUID serverId,
                                                              @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(deployments.view(deployments.createPlatform(orgId, serverId, body), true));
    }

    @PostMapping("/apps")
    public ResponseEntity<Map<String, Object>> createApp(@PathVariable UUID orgId,
                                                         @PathVariable UUID serverId,
                                                         @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(deployments.view(deployments.createApp(orgId, serverId, body), true));
    }

    @PutMapping("/{specId}")
    public Map<String, Object> update(@PathVariable UUID orgId, @PathVariable UUID serverId,
                                      @PathVariable UUID specId, @RequestBody Map<String, Object> body) {
        DeploymentSpec existing = deployments.get(orgId, specId);
        if (!existing.getServerId().equals(serverId)) {
            throw com.nebdev.devopsagent.saas.shared.ApiException.notFound("deployment_spec");
        }
        return deployments.view(deployments.update(orgId, specId, body), true);
    }

    @DeleteMapping("/{specId}")
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID serverId,
                                       @PathVariable UUID specId) {
        DeploymentSpec existing = deployments.get(orgId, specId);
        if (!existing.getServerId().equals(serverId)) {
            throw com.nebdev.devopsagent.saas.shared.ApiException.notFound("deployment_spec");
        }
        deployments.delete(orgId, specId);
        return ResponseEntity.noContent().build();
    }
}
