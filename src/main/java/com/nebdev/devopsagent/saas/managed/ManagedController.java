package com.nebdev.devopsagent.saas.managed;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/orgs/{orgId}/managed")
@Tag(name = "client")
public class ManagedController {

    private final ManagedService managed;

    public ManagedController(ManagedService managed) {
        this.managed = managed;
    }

    public record Grant(@NotBlank String staffEmail, @NotBlank String level, @Min(1) @Max(720) int hours,
                        @NotBlank String reason) {}

    public record Request(UUID serverId, @NotBlank String title, String description, String priority) {}

    public record Update(String status, Instant plannedFor, String report, List<UUID> operationIds) {}

    @GetMapping("/contracts")
    public List<Map<String, Object>> contracts(@PathVariable UUID orgId) {
        return managed.contracts(orgId).stream().map(ManagedService::contractView).toList();
    }

    @GetMapping("/support-access")
    public List<Map<String, Object>> grants(@PathVariable UUID orgId) {
        return managed.listGrants(orgId).stream().map(ManagedService::grantView).toList();
    }

    @PostMapping("/support-access")
    public ResponseEntity<Map<String, Object>> grant(@PathVariable UUID orgId, @Valid @RequestBody Grant g) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ManagedService.grantView(managed.grant(orgId, g.staffEmail(), g.level(), g.hours(), g.reason())));
    }

    @DeleteMapping("/support-access/{grantId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID orgId, @PathVariable UUID grantId) {
        managed.revokeGrant(orgId, grantId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/interventions")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> interventions(@PathVariable UUID orgId) {
        return managed.interventions(orgId).stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>(ManagedService.interventionView(i));
            m.put("operationIds", managed.linkedOperations(i.getId()));
            return m;
        }).toList();
    }

    @PostMapping("/interventions")
    public ResponseEntity<Map<String, Object>> request(@PathVariable UUID orgId, @Valid @RequestBody Request r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ManagedService.interventionView(
                managed.requestIntervention(orgId, r.serverId(), r.title(), r.description(), r.priority())));
    }

    @PatchMapping("/interventions/{id}")
    public Map<String, Object> update(@PathVariable UUID orgId, @PathVariable UUID id, @RequestBody Update u) {
        return ManagedService.interventionView(managed.updateIntervention(orgId, id, u.status(), u.plannedFor(),
                u.report(), u.operationIds()));
    }
}
