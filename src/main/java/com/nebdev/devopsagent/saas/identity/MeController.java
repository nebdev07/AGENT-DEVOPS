package com.nebdev.devopsagent.saas.identity;

import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compte de l'utilisateur connecté (session seulement : un jeton d'API n'a pas de « moi »). */
@RestController
@RequestMapping("/api/v2/me")
@Tag(name = "client")
public class MeController {

    private final AuthService auth;
    private final IdentityRepositories.Users users;

    public MeController(AuthService auth, IdentityRepositories.Users users) {
        this.auth = auth;
        this.users = users;
    }

    public record Profile(String displayName, String locale, String timeZone) {}

    public record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    public record Code(@NotBlank String code) {}

    public record Password(@NotBlank String password) {}

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> me() {
        Actor a = user();
        UserAccount u = users.findById(a.userId()).orElseThrow();
        Map<String, Object> m = userView(u);
        m.put("staffRole", a.staffRole());
        m.put("staffRolePendingMfa", auth.staffRolePendingMfa(u).orElse(null));
        return m;
    }

    @PatchMapping
    public Map<String, Object> update(@RequestBody Profile p) {
        return userView(auth.updateProfile(user().userId(), p.displayName(), p.locale(), p.timeZone()));
    }

    @PostMapping("/password")
    public Map<String, Object> password(@Valid @RequestBody PasswordChange p) {
        auth.changePassword(user().userId(), p.currentPassword(), p.newPassword());
        return Map.of("status", "ok");
    }

    @PostMapping("/mfa/setup")
    public Map<String, Object> mfaSetup() {
        AuthService.MfaSetup s = auth.setupMfa(user().userId());
        return Map.of("secret", s.secretBase32(), "otpauthUri", s.otpauthUri());
    }

    @PostMapping("/mfa/enable")
    public Map<String, Object> mfaEnable(@Valid @RequestBody Code c) {
        auth.enableMfa(user().userId(), c.code());
        return Map.of("mfaEnabled", true);
    }

    @PostMapping("/mfa/disable")
    public Map<String, Object> mfaDisable(@Valid @RequestBody Password p) {
        auth.disableMfa(user().userId(), p.password());
        return Map.of("mfaEnabled", false);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest http) {
        String h = http.getHeader("Authorization");
        auth.logout(h == null ? null : h.substring(Math.min(7, h.length())).strip());
        return Map.of("status", "ok");
    }

    @PostMapping("/logout-everywhere")
    public Map<String, Object> logoutEverywhere() {
        auth.logoutEverywhere(user().userId());
        return Map.of("status", "ok");
    }

    private static Actor user() {
        Actor a = Actor.require();
        if (a.type() != Actor.Type.USER) {
            throw ApiException.forbidden("user_required");
        }
        return a;
    }

    public static Map<String, Object> userView(UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("email", u.getEmail());
        m.put("displayName", u.getDisplayName());
        m.put("locale", u.getLocale());
        m.put("timeZone", u.getTimeZone());
        m.put("emailVerified", u.getEmailVerifiedAt() != null);
        m.put("mfaEnabled", u.isMfaEnabled());
        m.put("lastLoginAt", u.getLastLoginAt());
        return m;
    }
}
