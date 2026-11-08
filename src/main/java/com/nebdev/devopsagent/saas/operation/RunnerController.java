package com.nebdev.devopsagent.saas.operation;

import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * API interne du runner séparé (ADR-055). Authentification : jeton runner ({@code AUTOPILOT_RUNNER_TOKEN},
 * {@code ROLE_RUNNER}, voir {@code SaasSecurityConfig}). N'existe qu'en mode {@code remote} ; absente de l'OpenAPI
 * publique. Le runner identifie son instance par {@link RunnerContract#RUNNER_ID_HEADER} : une exécution ne peut être
 * rapportée que par le runner qui l'a réservée.
 */
@Hidden
@RestController
@RequestMapping("/api/v2/internal/runner")
public class RunnerController {

    private final ExecutionWorker worker;
    private final SaasProperties properties;

    public RunnerController(ExecutionWorker worker, SaasProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    @PostMapping("/claim")
    public ResponseEntity<RunnerContract.Job> claim(@RequestHeader(RunnerContract.RUNNER_ID_HEADER) String runnerId) {
        requireRemote();
        return worker.claimForRunner(runnerId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/executions/{id}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable UUID id,
                                          @RequestHeader(RunnerContract.RUNNER_ID_HEADER) String runnerId) {
        requireRemote();
        if (!worker.runnerHeartbeat(id, runnerId)) {
            throw ApiException.conflict("execution_not_owned", "Exécution terminée ou réservée par un autre runner.");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/executions/{id}/result")
    public ResponseEntity<Void> result(@PathVariable UUID id,
                                       @RequestHeader(RunnerContract.RUNNER_ID_HEADER) String runnerId,
                                       @RequestBody RunnerContract.JobResult result) {
        requireRemote();
        if (!worker.runnerResult(id, runnerId, result)) {
            throw ApiException.conflict("execution_not_owned", "Exécution terminée ou réservée par un autre runner.");
        }
        return ResponseEntity.noContent().build();
    }

    private void requireRemote() {
        if (properties.getRunner().isEmbedded()) {
            throw ApiException.notFound("runner_api");
        }
    }
}
