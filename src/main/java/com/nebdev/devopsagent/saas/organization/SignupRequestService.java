package com.nebdev.devopsagent.saas.organization;

import com.nebdev.devopsagent.platform.PasswordHashes;
import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.identity.IdentityRepositories;
import com.nebdev.devopsagent.saas.identity.UserAccount;
import com.nebdev.devopsagent.saas.notification.NotificationService;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class SignupRequestService {

    private final SignupRequestRepository requests;
    private final IdentityRepositories.Users users;
    private final OrganizationService organizations;
    private final NotificationService notifications;
    private final AuditService audit;
    private final SaasProperties properties;
    private final String bootstrapAdminEmail;

    public SignupRequestService(SignupRequestRepository requests, IdentityRepositories.Users users,
                                OrganizationService organizations, NotificationService notifications,
                                AuditService audit, SaasProperties properties,
                                @Value("${AUTOPILOT_BOOTSTRAP_ADMIN_EMAIL:}") String bootstrapAdminEmail) {
        this.requests = requests;
        this.users = users;
        this.organizations = organizations;
        this.notifications = notifications;
        this.audit = audit;
        this.properties = properties;
        this.bootstrapAdminEmail = bootstrapAdminEmail == null ? "" : bootstrapAdminEmail.strip();
    }

    /** Toujours « accepté » côté client pour éviter l'énumération d'e-mails. */
    @Transactional
    public void submit(String email, String displayName, String organizationName, String locale, String timeZone,
                       String currency, String message, String ip, String userAgent) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (users.findByEmail(normalized).isPresent()) {
            return;
        }
        if (requests.findByEmailIgnoreCaseAndStatus(normalized, "PENDING").isPresent()) {
            return;
        }
        SignupRequest req = new SignupRequest(normalized, displayName, organizationName.strip(), locale, timeZone,
                currency == null || currency.isBlank() ? "USD" : currency.strip().toUpperCase(Locale.ROOT),
                message == null ? null : message.strip(), ip, userAgent);
        requests.save(req);
        audit.record(null, AuditService.Entry.success("signup.requested", "signup_request", req.getId(),
                Map.of("email", normalized, "organizationName", req.getOrganizationName())));
        if (!bootstrapAdminEmail.isBlank()) {
            String adminUrl = properties.getPublicUrl().replaceAll("/$", "") + "/app/platform";
            notifications.email(null, null, bootstrapAdminEmail, "signup_admin",
                    locale == null || locale.isBlank() ? "fr" : locale,
                    req.getDisplayName().isBlank() ? normalized : req.getDisplayName(), normalized,
                    req.getOrganizationName(), adminUrl);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPending() {
        return requests.findByStatusOrderByCreatedAtDesc("PENDING").stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> approve(UUID id, com.nebdev.devopsagent.saas.shared.Actor admin) {
        SignupRequest req = requests.findById(id)
                .orElseThrow(() -> ApiException.notFound("signup_request"));
        if (!"PENDING".equals(req.getStatus())) {
            throw ApiException.conflict("signup_not_pending");
        }
        if (users.findByEmail(req.getEmail()).isPresent()) {
            throw ApiException.conflict("email_taken");
        }
        String password = PasswordHashes.randomPassword();
        OrganizationService.Registered reg = organizations.register(req.getEmail(), password, req.getDisplayName(),
                req.getOrganizationName(), req.getLocale(), req.getTimeZone(), req.getCurrency(),
                "admin-approval", "signup-approval");
        UserAccount user = reg.session().user();
        req.markApproved(admin.userId(), user.getId(), reg.organization().getId());
        requests.save(req);
        String loginUrl = properties.getPublicUrl().replaceAll("/$", "") + "/app/login";
        notifications.email(reg.organization().getId(), user.getId(), req.getEmail(), "signup_approved",
                user.getLocale(), user.getDisplayName().isBlank() ? req.getEmail() : user.getDisplayName(),
                loginUrl, req.getEmail(), password);
        audit.record(null, admin, AuditService.Entry.success("signup.approved", "signup_request", req.getId(),
                Map.of("userId", user.getId().toString(), "organizationId", reg.organization().getId().toString())));
        return view(req);
    }

    @Transactional
    public Map<String, Object> reject(UUID id, com.nebdev.devopsagent.saas.shared.Actor admin) {
        SignupRequest req = requests.findById(id)
                .orElseThrow(() -> ApiException.notFound("signup_request"));
        if (!"PENDING".equals(req.getStatus())) {
            throw ApiException.conflict("signup_not_pending");
        }
        req.markRejected(admin.userId());
        requests.save(req);
        notifications.email(null, null, req.getEmail(), "signup_rejected", req.getLocale(),
                req.getDisplayName().isBlank() ? req.getEmail() : req.getDisplayName());
        audit.record(null, admin, AuditService.Entry.success("signup.rejected", "signup_request", req.getId(),
                Map.of()));
        return view(req);
    }

    private Map<String, Object> view(SignupRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("email", r.getEmail());
        m.put("displayName", r.getDisplayName());
        m.put("organizationName", r.getOrganizationName());
        m.put("locale", r.getLocale());
        m.put("currency", r.getCurrency());
        m.put("message", r.getMessage());
        m.put("status", r.getStatus());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }
}
