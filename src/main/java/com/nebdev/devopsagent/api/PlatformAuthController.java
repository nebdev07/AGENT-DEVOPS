package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.platform.AccessControl;
import com.nebdev.devopsagent.platform.AuthService;
import com.nebdev.devopsagent.platform.PlatformAccessException;
import com.nebdev.devopsagent.platform.PlatformModels;
import com.nebdev.devopsagent.platform.PlatformPrincipal;
import com.nebdev.devopsagent.platform.PlatformProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platform/auth")
public class PlatformAuthController {

    private final AuthService auth;
    private final AccessControl access;
    private final PlatformProperties properties;
    private final com.nebdev.devopsagent.platform.AuthRateLimiter limiter;

    public PlatformAuthController(AuthService auth, AccessControl access, PlatformProperties properties,
                                  com.nebdev.devopsagent.platform.AuthRateLimiter limiter) {
        this.limiter = limiter;
        this.auth = auth;
        this.access = access;
        this.properties = properties;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) throws IOException {
        if (body == null || body.email() == null || body.password() == null) {
            throw new IllegalArgumentException("E-mail et mot de passe requis.");
        }
        limiter.check("login", clientIp(request), body.email());
        PlatformModels.Session session = auth.loginWithPassword(body.email(), body.password());
        return sessionResponse(session, request, response);
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody PasswordChange body) throws IOException {
        PlatformPrincipal principal = access.requireLogin();
        if (principal == null) {
            throw PlatformAccessException.unauthorized("Connexion requise.");
        }
        if (body == null || body.currentPassword() == null || body.newPassword() == null) {
            throw new IllegalArgumentException("Mot de passe actuel et nouveau requis.");
        }
        auth.changePassword(principal.accountId(), body.currentPassword(), body.newPassword());
        PlatformPrincipal refreshed = auth.principalForSession(currentSessionId());
        return Map.of("status", "ok", "mustChangePassword", refreshed.mustChangePassword(), "me", auth.me(refreshed));
    }

    @PostMapping("/request")
    public Map<String, Object> request(@RequestBody MagicRequest body, HttpServletRequest http) throws IOException {
        if (body == null || body.email() == null || body.email().isBlank()) {
            throw new IllegalArgumentException("Indique ton adresse e-mail.");
        }
        limiter.check("magic", clientIp(http), body.email());
        return auth.requestMagicLink(body.email());
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody MagicRequest body, HttpServletRequest http) throws IOException {
        if (body == null || body.email() == null || body.email().isBlank()) {
            throw new IllegalArgumentException("Indique ton adresse e-mail.");
        }
        limiter.check("forgot", clientIp(http), body.email());
        return auth.requestPasswordReset(body.email());
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@RequestBody PasswordReset body, HttpServletRequest http) throws IOException {
        limiter.check("reset", clientIp(http), null);
        if (body == null || body.token() == null || body.token().isBlank()) {
            throw new IllegalArgumentException("Jeton de réinitialisation requis.");
        }
        if (Boolean.TRUE.equals(body.useDefault())) {
            auth.completePasswordResetToDefault(body.token());
            return Map.of(
                    "status", "ok",
                    "mustChangePassword", true,
                    "message", "Mot de passe usine rétabli. Connecte-toi puis change-le.");
        }
        if (body.newPassword() == null || body.newPassword().isBlank()) {
            throw new IllegalArgumentException("Nouveau mot de passe requis.");
        }
        auth.completePasswordReset(body.token(), body.newPassword());
        return Map.of("status", "ok", "mustChangePassword", false, "message", "Mot de passe mis à jour. Connecte-toi.");
    }

    @GetMapping("/consume")
    public Map<String, Object> consume(@RequestParam("token") String token,
                                       HttpServletRequest request,
                                       HttpServletResponse response) throws IOException {
        limiter.check("consume", clientIp(request), null);
        PlatformModels.Session session = auth.consumeMagicLink(token);
        return sessionResponse(session, request, response);
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletResponse response) throws IOException {
        auth.logout(currentSessionId());
        Cookie cookie = new Cookie(properties.getCookieName(), "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setSecure(properties.isCookieSecure());
        response.addCookie(cookie);
        return Map.of("status", "ok");
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        PlatformPrincipal principal = access.requireLogin();
        if (principal == null) {
            return Map.of("mode", "local", "platform", false);
        }
        return auth.me(principal);
    }

    private Map<String, Object> sessionResponse(PlatformModels.Session session,
                                                HttpServletRequest request,
                                                HttpServletResponse response) throws IOException {
        Cookie cookie = new Cookie(properties.getCookieName(), session.id());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(properties.getSessionHours() * 3600);
        cookie.setSecure(properties.isCookieSecure());
        response.addCookie(cookie);
        PlatformPrincipal principal = auth.principalForSession(session.id());
        AccessControl.bind(request, principal, session.id());
        Map<String, Object> out = new LinkedHashMap<>(auth.me(principal));
        out.put("status", "ok");
        out.put("sessionId", session.id());
        return out;
    }

    /**
     * IP du client. Derrière un seul proxy de confiance (Traefik), la dernière entrée de X-Forwarded-For
     * est celle ajoutée par le proxy : les entrées précédentes, fournies par le client, sont ignorées.
     */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].strip();
        }
        return request.getRemoteAddr();
    }

    private static String currentSessionId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        Object id = attrs.getAttribute(AccessControl.ATTR_SESSION_ID, RequestAttributes.SCOPE_REQUEST);
        if (id instanceof String s) {
            return s;
        }
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
            Object fromReq = servletAttrs.getRequest().getAttribute(AccessControl.ATTR_SESSION_ID);
            return fromReq instanceof String s ? s : null;
        }
        return null;
    }

    public record LoginRequest(String email, String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";
        }
    }

    public record PasswordChange(String currentPassword, String newPassword) {
        @Override
        public String toString() {
            return "PasswordChange[currentPassword=***, newPassword=***]";
        }
    }

    public record MagicRequest(String email) {}

    public record PasswordReset(String token, String newPassword, Boolean useDefault) {
        @Override
        public String toString() {
            return "PasswordReset[token=***, newPassword=***, useDefault=" + useDefault + "]";
        }
    }

    @ExceptionHandler(PlatformAccessException.class)
    public ResponseEntity<Map<String, String>> access(PlatformAccessException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> io(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }
}
