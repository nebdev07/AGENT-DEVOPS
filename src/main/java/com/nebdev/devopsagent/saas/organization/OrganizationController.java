package com.nebdev.devopsagent.saas.organization;

import com.nebdev.devopsagent.saas.audit.AuditEvent;
import com.nebdev.devopsagent.saas.audit.AuditService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/orgs")
@Tag(name = "client")
public class OrganizationController {

    private final OrganizationService organizations;
    private final OrgAccess access;
    private final AuditService audit;

    public OrganizationController(OrganizationService organizations, OrgAccess access, AuditService audit) {
        this.organizations = organizations;
        this.access = access;
        this.audit = audit;
    }

    public record CreateOrg(@NotBlank String name, String locale, String timeZone, String currency) {}

    public record UpdateOrg(String name, String locale, String timeZone, String currency, Boolean requireFourEyes) {}

    public record Invite(@NotBlank String email, @NotBlank String role) {}

    public record RoleChange(@NotBlank String role) {}

    public record NewRole(@NotBlank String code, String label, @NotEmpty Set<String> permissions) {}


    @GetMapping
    public List<Map<String, Object>> mine() {
        return organizations.mine();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateOrg r) {
        Organization o = organizations.create(r.name(), r.locale(), r.timeZone(), r.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrganizationService.view(o, OrgPermissions.OWNER, Set.of()));
    }

    @GetMapping("/{orgId}")
    public Map<String, Object> get(@PathVariable UUID orgId) {
        return organizations.get(orgId);
    }

    @PatchMapping("/{orgId}")
    public Map<String, Object> update(@PathVariable UUID orgId, @RequestBody UpdateOrg r) {
        return organizations.update(orgId, r.name(), r.locale(), r.timeZone(), r.currency(), r.requireFourEyes());
    }

    @GetMapping("/{orgId}/members")
    public List<OrganizationService.MemberView> members(@PathVariable UUID orgId) {
        return organizations.members(orgId);
    }

    @PostMapping("/{orgId}/invitations")
    public ResponseEntity<Map<String, Object>> invite(@PathVariable UUID orgId, @Valid @RequestBody Invite r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organizations.invite(orgId, r.email(), r.role()));
    }

    @PutMapping("/{orgId}/members/{userId}/role")
    public OrganizationService.MemberView role(@PathVariable UUID orgId, @PathVariable UUID userId,
                                               @Valid @RequestBody RoleChange r) {
        return organizations.changeRole(orgId, userId, r.role());
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    public ResponseEntity<Void> remove(@PathVariable UUID orgId, @PathVariable UUID userId) {
        organizations.remove(orgId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{orgId}/roles")
    public List<Map<String, Object>> roles(@PathVariable UUID orgId) {
        return organizations.roles(orgId).stream().map(OrganizationController::roleView).toList();
    }

    @PostMapping("/{orgId}/roles")
    public ResponseEntity<Map<String, Object>> newRole(@PathVariable UUID orgId, @Valid @RequestBody NewRole r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roleView(organizations.createRole(orgId, r.code(), r.label(), r.permissions())));
    }

    @GetMapping("/{orgId}/audit-events")
    @Transactional(readOnly = true)
    public Map<String, Object> auditEvents(@PathVariable UUID orgId, @RequestParam(required = false) Instant from,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        access.require(orgId, OrgPermissions.AUDIT_READ);
        Page<AuditEvent> p = audit.page(orgId, from, page, size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", AuditService.view(p.getContent()));
        out.put("page", p.getNumber());
        out.put("totalPages", p.getTotalPages());
        out.put("total", p.getTotalElements());
        return out;
    }

    @GetMapping("/{orgId}/audit-events/verify")
    @Transactional(readOnly = true)
    public AuditService.Verification verify(@PathVariable UUID orgId) {
        access.require(orgId, OrgPermissions.AUDIT_READ);
        return audit.verify(orgId);
    }

    @GetMapping("/{orgId}/permissions")
    public Set<String> catalog(@PathVariable UUID orgId) {
        return OrgPermissions.ALL;
    }

    static Map<String, Object> roleView(OrgRole r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getCode());
        m.put("label", r.getLabel());
        m.put("system", r.isSystem());
        m.put("permissions", r.getPermissions());
        return m;
    }
}
