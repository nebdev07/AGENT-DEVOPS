package com.nebdev.devopsagent.saas.billing;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "client")
public class BillingController {

    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    public record ChangePlan(@NotBlank String plan, String period) {}

    @GetMapping("/api/v2/public/plans")
    public List<Map<String, Object>> plans() {
        return billing.publicCatalog();
    }

    @GetMapping("/api/v2/orgs/{orgId}/billing")
    public Map<String, Object> overview(@PathVariable UUID orgId) {
        return billing.overview(orgId);
    }

    @PostMapping("/api/v2/orgs/{orgId}/billing/change-plan")
    public Map<String, Object> changePlan(@PathVariable UUID orgId, @Valid @RequestBody ChangePlan r) {
        return billing.changePlan(orgId, r.plan(), r.period());
    }

    @PostMapping("/api/v2/orgs/{orgId}/billing/cancel")
    public Map<String, Object> cancel(@PathVariable UUID orgId, @RequestParam(defaultValue = "true") boolean atPeriodEnd) {
        return billing.cancel(orgId, atPeriodEnd);
    }

    /** Webhook prestataire : signature vérifiée, idempotent par identifiant d'événement. */
    @PostMapping("/api/v2/public/payments/{provider}/webhook")
    public Map<String, Object> webhook(@PathVariable String provider, @RequestBody byte[] body, HttpServletRequest http) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(http.getHeaderNames())) {
            headers.put(name, http.getHeader(name));
        }
        return Map.of("result", billing.handleWebhook(provider, headers, body));
    }
}
