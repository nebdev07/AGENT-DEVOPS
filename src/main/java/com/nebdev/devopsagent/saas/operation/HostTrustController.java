package com.nebdev.devopsagent.saas.operation;

import com.nebdev.devopsagent.saas.server.ServerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/orgs/{orgId}/servers/{serverId}/host-key")
@Tag(name = "client")
public class HostTrustController {

    private final HostTrustService hostTrust;

    public HostTrustController(HostTrustService hostTrust) {
        this.hostTrust = hostTrust;
    }

    public record Fingerprint(@NotBlank String fingerprint) {}

    @PostMapping("/scan")
    public Map<String, Object> scan(@PathVariable UUID orgId, @PathVariable UUID serverId) {
        return hostTrust.scan(orgId, serverId);
    }

    @PostMapping("/trust")
    public Map<String, Object> trust(@PathVariable UUID orgId, @PathVariable UUID serverId,
                                     @Valid @RequestBody Fingerprint f) {
        return hostTrust.trust(orgId, serverId, f.fingerprint());
    }

    /** Implémente le port du module serveur : un serveur occupé ne se supprime pas. */
    @Component
    static class ActiveExecutions implements ServerService.ActiveExecutionCheck {
        private final OperationRepositories.Executions executions;

        ActiveExecutions(OperationRepositories.Executions executions) {
            this.executions = executions;
        }

        @Override
        public boolean hasActiveExecution(UUID serverId) {
            return executions.activeOnServer(serverId);
        }
    }
}
