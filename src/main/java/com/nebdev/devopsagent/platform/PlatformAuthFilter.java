package com.nebdev.devopsagent.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Profil {@code platform} : session obligatoire pour l’API et le cockpit, sauf santé / auth / pages publiques.
 */
@Component
@Profile("platform")
@Order(-100)
public class PlatformAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PAGES = Set.of(
            "/", "/index.html", "/login.html", "/change-password.html", "/reset-password.html",
            "/forgot-password.html", "/admin.html", "/favicon.ico", "/cargo.html"
    );

    private static final Set<String> ALLOWED_WHILE_MUST_CHANGE = Set.of(
            "/api/v1/platform/auth/me",
            "/api/v1/platform/auth/logout",
            "/api/v1/platform/auth/password",
            "/change-password.html",
            "/login.html"
    );

    private final AuthService auth;
    private final PlatformProperties properties;

    public PlatformAuthFilter(AuthService auth, PlatformProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isPublic(path, request.getMethod())) {
            bindIfPresent(request);
            chain.doFilter(request, response);
            return;
        }
        String sessionId = readSession(request);
        if (sessionId == null || sessionId.isBlank()) {
            deny(response, 401, "Connexion requise.", "AUTH_REQUIRED");
            return;
        }
        try {
            PlatformPrincipal principal = auth.principalForSession(sessionId);
            AccessControl.bind(request, principal, sessionId);
            if (principal.mustChangePassword() && !allowedWhileMustChange(path)) {
                deny(response, 403, "Change ton mot de passe avant de continuer.", "MUST_CHANGE_PASSWORD");
                return;
            }
            chain.doFilter(request, response);
        } catch (PlatformAccessException ex) {
            String code = ex.status() == 401 ? "SESSION_EXPIRED" : null;
            deny(response, ex.status(), ex.getMessage(), code);
        }
    }

    private void bindIfPresent(HttpServletRequest request) throws IOException {
        String sessionId = readSession(request);
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            PlatformPrincipal principal = auth.principalForSession(sessionId);
            AccessControl.bind(request, principal, sessionId);
        } catch (PlatformAccessException ignored) {
            // page publique : session invalide = anonyme
        }
    }

    private String readSession(HttpServletRequest request) {
        String header = request.getHeader("X-Platform-Session");
        if (header != null && !header.isBlank()) {
            return header.strip();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = properties.getCookieName();
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    static boolean isPublic(String path, String method) {
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if ("/api/v1/health".equals(path)) {
            return true;
        }
        if ("/api/v1/platform/auth/request".equals(path)
                || "/api/v1/platform/auth/consume".equals(path)
                || "/api/v1/platform/auth/login".equals(path)
                || "/api/v1/platform/auth/forgot-password".equals(path)
                || "/api/v1/platform/auth/reset-password".equals(path)) {
            return true;
        }
        if (PUBLIC_PAGES.contains(path)) {
            return true;
        }
        return path.startsWith("/static/")
                || path.endsWith(".css")
                || path.endsWith(".js")
                || path.endsWith(".svg")
                || path.endsWith(".png")
                || path.endsWith(".ico");
    }

    private static boolean allowedWhileMustChange(String path) {
        return ALLOWED_WHILE_MUST_CHANGE.contains(path)
                || path.endsWith(".css")
                || path.endsWith(".js");
    }

    private static void deny(HttpServletResponse response, int status, String message, String code)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String safe = message == null ? "Accès refusé." : message.replace("\"", "'");
        if (code == null || code.isBlank()) {
            response.getWriter().write("{\"error\":\"" + safe + "\"}");
        } else {
            response.getWriter().write("{\"error\":\"" + safe + "\",\"code\":\"" + code + "\"}");
        }
    }
}
