package com.nebdev.devopsagent.saas.organization;

import com.nebdev.devopsagent.saas.identity.AuthService;
import com.nebdev.devopsagent.saas.identity.MeController;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** API publique d'authentification (anonyme). Réponses identiques que le compte existe ou non. */
@RestController
@RequestMapping("/api/v2/public/auth")
@Tag(name = "public")
public class PublicAuthController {

    private final AuthService auth;
    private final OrganizationService organizations;

    public PublicAuthController(AuthService auth, OrganizationService organizations) {
        this.auth = auth;
        this.organizations = organizations;
    }

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(min = 12, max = 128) String password,
                                  @Size(max = 100) String displayName,
                                  @NotBlank @Size(max = 120) String organizationName,
                                  String locale, String timeZone, String currency) {
        @Override
        public String toString() {
            return "RegisterRequest[" + email + ", password=***]";
        }
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password, String totp) {
        @Override
        public String toString() {
            return "LoginRequest[" + email + ", password=***]";
        }
    }

    public record EmailRequest(@NotBlank String email) {}

    public record ResetRequest(@NotBlank String token, @NotBlank String newPassword) {}

    public record TokenRequest(@NotBlank String token) {}

    public record AcceptInvitation(@NotBlank String token, @NotBlank String password, String displayName) {}

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest r, HttpServletRequest http) {
        OrganizationService.Registered reg = organizations.register(r.email(), r.password(), r.displayName(),
                r.organizationName(), r.locale() == null || r.locale().isBlank()
                        ? org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage() : r.locale(), r.timeZone(), r.currency(), http.getRemoteAddr(), http.getHeader("User-Agent"));
        Map<String, Object> body = session(reg.session());
        body.put("organizationId", reg.organization().getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest r, HttpServletRequest http) {
        return session(auth.login(r.email(), r.password(), r.totp(), http.getRemoteAddr(), http.getHeader("User-Agent")));
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<Map<String, Object>> requestReset(@Valid @RequestBody EmailRequest r, HttpServletRequest http) {
        auth.requestPasswordReset(r.email(), http.getRemoteAddr());
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }

    @PostMapping("/password-reset/confirm")
    public Map<String, Object> confirmReset(@Valid @RequestBody ResetRequest r) {
        auth.resetPassword(r.token(), r.newPassword());
        return Map.of("status", "ok");
    }

    @PostMapping("/verify-email")
    public Map<String, Object> verify(@Valid @RequestBody TokenRequest r) {
        auth.verifyEmail(r.token());
        return Map.of("status", "ok");
    }

    @PostMapping("/invitations/accept")
    public Map<String, Object> accept(@Valid @RequestBody AcceptInvitation r, HttpServletRequest http) {
        OrganizationService.Registered reg = organizations.acceptInvitation(r.token(), r.password(), r.displayName(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
        Map<String, Object> body = session(reg.session());
        body.put("organizationId", reg.organization().getId());
        return body;
    }

    static Map<String, Object> session(AuthService.Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", s.token());
        m.put("expiresAt", s.expiresAt());
        m.put("user", MeController.userView(s.user()));
        return m;
    }
}
