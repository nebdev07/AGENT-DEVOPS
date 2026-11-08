package com.nebdev.devopsagent.saas.operation;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/orgs/{orgId}/operations")
@Tag(name = "client")
public class OperationController {

    private final OperationService operations;

    public OperationController(OperationService operations) {
        this.operations = operations;
    }

    public record Propose(@NotNull UUID serverId, @NotBlank String type, Map<String, Object> parameters,
                          @Size(max = 500) String reason) {}

    public record Approve(@NotBlank String planHash, @Size(max = 500) String comment) {}

    public record Reject(@Size(max = 500) String comment) {}

    @GetMapping("/catalog")
    public List<Map<String, Object>> catalog(@PathVariable UUID orgId) {
        return Arrays.stream(OperationType.values()).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", t.name());
            m.put("label", t.label());
            m.put("risk", t.risk().name());
            m.put("autoApproved", t.autoApproved());
            return m;
        }).toList();
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID orgId, @RequestParam(required = false) UUID serverId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return operations.list(orgId, serverId, status, page, size).stream().map(OperationService::view).toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> propose(@PathVariable UUID orgId, @Valid @RequestBody Propose r,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        OperationEntities.Operation op = operations.propose(orgId, r.serverId(), r.type(), r.parameters(), r.reason(), key);
        return ResponseEntity.status(HttpStatus.CREATED).body(OperationService.view(op));
    }

    @GetMapping("/{opId}")
    public Map<String, Object> get(@PathVariable UUID orgId, @PathVariable UUID opId) {
        OperationService.Detail d = operations.get(orgId, opId);
        Map<String, Object> m = new LinkedHashMap<>(OperationService.view(d.operation()));
        m.put("approvals", d.approvals().stream().map(OperationService::view).toList());
        m.put("executions", d.executions().stream().map(OperationService::view).toList());
        return m;
    }

    @PostMapping("/{opId}/approve")
    public Map<String, Object> approve(@PathVariable UUID orgId, @PathVariable UUID opId, @Valid @RequestBody Approve r) {
        return OperationService.view(operations.approve(orgId, opId, r.planHash(), r.comment()));
    }

    @PostMapping("/{opId}/reject")
    public Map<String, Object> reject(@PathVariable UUID orgId, @PathVariable UUID opId, @RequestBody(required = false) Reject r) {
        return OperationService.view(operations.reject(orgId, opId, r == null ? null : r.comment()));
    }

    @PostMapping("/{opId}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID orgId, @PathVariable UUID opId) {
        return OperationService.view(operations.cancel(orgId, opId));
    }

    @PostMapping("/{opId}/submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable UUID orgId, @PathVariable UUID opId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(OperationService.view(operations.submitDraft(orgId, opId)));
    }
}
