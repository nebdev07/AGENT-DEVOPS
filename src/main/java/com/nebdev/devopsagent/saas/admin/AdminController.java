package com.nebdev.devopsagent.saas.admin;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.billing.BillingService;
import com.nebdev.devopsagent.saas.identity.IdentityRepositories;
import com.nebdev.devopsagent.saas.identity.PlatformStaff;
import com.nebdev.devopsagent.saas.identity.UserAccount;
import com.nebdev.devopsagent.saas.managed.ManagedService;
import com.nebdev.devopsagent.saas.organization.Organization;
import com.nebdev.devopsagent.saas.organization.OrganizationRepositories;
import com.nebdev.devopsagent.saas.organization.SignupRequestService;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Back-office INOVNE (personnel plateforme). Ne lit que des métadonnées (organisation, abonnement, compteurs) :
 * aucun serveur, aucune opération, aucun audit client sans accès support accordé par le client (ADR-052).
 */
@RestController
@RequestMapping("/api/v2/admin")
@Tag(name = "admin")
public class AdminController {

    @PersistenceContext
    private EntityManager em;
    private final OrganizationRepositories.Organizations orgs;
    private final IdentityRepositories.Users users;
    private final IdentityRepositories.Staff staff;
    private final ManagedService managed;
    private final BillingService billing;
    private final AuditService audit;
    private final RlsContext rls;
    private final SignupRequestService signupRequests;

    public AdminController(OrganizationRepositories.Organizations orgs, IdentityRepositories.Users users,
                           IdentityRepositories.Staff staff, ManagedService managed, BillingService billing,
                           AuditService audit, RlsContext rls, SignupRequestService signupRequests) {
        this.orgs = orgs;
        this.users = users;
        this.staff = staff;
        this.managed = managed;
        this.billing = billing;
        this.audit = audit;
        this.rls = rls;
        this.signupRequests = signupRequests;
    }

    public record NewStaff(@NotBlank String email, @NotBlank String role) {}

    public record NewContract(@NotBlank String serviceLevel, LocalDate startsOn, LocalDate endsOn,
                              @Min(15) @Max(10080) int responseTimeMinutes, String scope) {}

    public record MarkPaid(String reference) {}

    public record Suspend(@NotBlank String status) {}

    @GetMapping("/organizations")
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> organizations() {
        rls.enterPlatform();
        List<Object[]> rows = em.createNativeQuery("""
                select o.id, o.name, o.slug, o.customer_type, o.status, o.created_at,
                       s.plan_code, s.status as sub_status, s.current_period_end,
                       (select count(*) from server x where x.organization_id = o.id) as servers,
                       (select count(*) from membership m where m.organization_id = o.id and m.status = 'ACTIVE') as members
                from organization o left join subscription s on s.organization_id = o.id
                order by o.created_at desc limit 500""").getResultList();
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]);
            m.put("name", r[1]);
            m.put("slug", r[2]);
            m.put("customerType", r[3]);
            m.put("status", r[4]);
            m.put("createdAt", r[5]);
            m.put("plan", r[6]);
            m.put("subscriptionStatus", r[7]);
            m.put("currentPeriodEnd", r[8]);
            m.put("servers", r[9]);
            m.put("members", r[10]);
            return m;
        }).toList();
    }

    @PostMapping("/organizations/{orgId}/status")
    @Transactional
    public Map<String, Object> setStatus(@PathVariable UUID orgId, @Valid @RequestBody Suspend s) {
        Actor a = requireAdmin();
        if (!List.of("ACTIVE", "SUSPENDED").contains(s.status())) {
            throw ApiException.badRequest("invalid_status");
        }
        rls.enterOrg(orgId);
        Organization o = orgs.findById(orgId).orElseThrow(() -> ApiException.notFound("organization"));
        o.setStatus(s.status());
        audit.record(orgId, a, AuditService.Entry.success("admin.organization_status", "organization", orgId,
                Map.of("status", s.status())));
        return Map.of("id", orgId, "status", o.getStatus());
    }

    @PostMapping("/organizations/{orgId}/managed-contracts")
    public ResponseEntity<Map<String, Object>> contract(@PathVariable UUID orgId, @Valid @RequestBody NewContract c) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ManagedService.contractView(managed.createContract(orgId,
                c.serviceLevel(), c.startsOn(), c.endsOn(), c.responseTimeMinutes(), c.scope())));
    }

    @PostMapping("/invoices/{invoiceId}/mark-paid")
    public Map<String, Object> markPaid(@PathVariable UUID invoiceId, @RequestBody(required = false) MarkPaid r) {
        return billing.markPaidByAdmin(invoiceId, r == null ? null : r.reference());
    }

    @GetMapping("/support-accesses")
    public List<Map<String, Object>> myAccesses() {
        return managed.myAccesses();
    }

    @GetMapping("/signup-requests")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> signupRequests() {
        requireAdmin();
        rls.enterPlatform();
        return signupRequests.listPending();
    }

    @PostMapping("/signup-requests/{id}/approve")
    @Transactional
    public Map<String, Object> approveSignup(@PathVariable UUID id) {
        Actor a = requireAdmin();
        rls.enterPlatform();
        return signupRequests.approve(id, a);
    }

    @PostMapping("/signup-requests/{id}/reject")
    @Transactional
    public Map<String, Object> rejectSignup(@PathVariable UUID id) {
        Actor a = requireAdmin();
        rls.enterPlatform();
        return signupRequests.reject(id, a);
    }

    @GetMapping("/staff")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> staffList() {
        requireAdmin();
        return staff.findAll().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", s.getUserId());
            m.put("role", s.getRole());
            Optional<UserAccount> u = users.findById(s.getUserId());
            m.put("email", u.map(UserAccount::getEmail).orElse(""));
            // Sans MFA, le rôle est inactif (AuthService.actorOf) : l'administrateur le voit ici.
            m.put("mfaEnabled", u.map(UserAccount::isMfaEnabled).orElse(false));
            return m;
        }).toList();
    }

    @PostMapping("/staff")
    @Transactional
    public ResponseEntity<Map<String, Object>> addStaff(@Valid @RequestBody NewStaff r) {
        Actor a = requireAdmin();
        UserAccount u = users.findByEmail(r.email()).orElseThrow(() -> ApiException.notFound("user"));
        PlatformStaff s;
        try {
            s = staff.findById(u.getId()).map(x -> {
                x.setRole(r.role());
                return x;
            }).orElseGet(() -> staff.save(new PlatformStaff(u.getId(), r.role())));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_role");
        }
        rls.enterPlatform();
        audit.record(null, a, AuditService.Entry.success("admin.staff_set", "user", u.getId(),
                Map.of("email", u.getEmail(), "role", s.getRole())));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", u.getId(), "role", s.getRole()));
    }

    @DeleteMapping("/staff/{userId}")
    @Transactional
    public ResponseEntity<Void> removeStaff(@PathVariable UUID userId) {
        Actor a = requireAdmin();
        if (userId.equals(a.userId())) {
            throw ApiException.conflict("cannot_remove_self");
        }
        staff.deleteById(userId);
        rls.enterPlatform();
        audit.record(null, a, AuditService.Entry.success("admin.staff_removed", "user", userId, Map.of()));
        return ResponseEntity.noContent().build();
    }

    private static Actor requireAdmin() {
        Actor a = Actor.require();
        if (!a.isPlatformAdmin()) {
            throw ApiException.forbidden("platform_admin_only");
        }
        return a;
    }
}
