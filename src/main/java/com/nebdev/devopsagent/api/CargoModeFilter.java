package com.nebdev.devopsagent.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Instance publique (ADR-030) : santé seulement. Le CHANGE reste sur le poste opérateur.
 */
@Component
@Profile("cargo")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CargoModeFilter extends OncePerRequestFilter {

    private static final Set<String> PAGES = Set.of("/", "/index.html", "/cargo.html", "/favicon.ico");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (allowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"Instance cargo : pas de CHANGE. Le cerveau reste sur le poste opérateur.\"}");
    }

    static boolean allowed(HttpServletRequest request) {
        String method = request.getMethod();
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        if (!read) {
            return false;
        }
        String path = request.getRequestURI();
        return "/api/v1/health".equals(path) || PAGES.contains(path);
    }
}
