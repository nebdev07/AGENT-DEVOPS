package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.ssh.LongJobTracker;
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
import java.util.Optional;

/**
 * Après la session : vérifie permission + grant serveur quand la route l’exige (ADR-046).
 */
@Component
@Profile("platform")
@Order(-90)
public class PlatformRbacFilter extends OncePerRequestFilter {

    private final AccessControl access;
    private final ObjectMapper mapper;
    private final LongJobTracker jobs;

    public PlatformRbacFilter(AccessControl access, ObjectMapper mapper, LongJobTracker jobs) {
        this.access = access;
        this.mapper = mapper;
        this.jobs = jobs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/") || PlatformAuthFilter.isPublic(path, request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        Optional<RoutePermissions.Rule> rule = RoutePermissions.match(request.getMethod(), path);
        if (rule.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest effective = request;
        byte[] body = null;
        String ct = request.getContentType();
        boolean mayHaveJson = ct != null && ct.toLowerCase().contains("json")
                && !"GET".equalsIgnoreCase(request.getMethod())
                && !"HEAD".equalsIgnoreCase(request.getMethod());
        if (mayHaveJson) {
            CachedBodyRequest cached = new CachedBodyRequest(request);
            body = cached.body();
            effective = cached;
        }
        try {
            if (rule.get().serverScoped()) {
                Target t = resolveTarget(effective, body);
                Optional<String> jobId = RoutePermissions.jobId(path);
                if (jobId.isPresent()) {
                    // Suivi de tâche : le serveur est celui de la tâche, pas un paramètre client.
                    String jobHost = jobs.find(jobId.get()).map(LongJobTracker.Job::host).orElse(null);
                    if (jobHost == null || jobHost.isBlank()) {
                        access.requireServerOrAdmin(rule.get().permission());
                    } else {
                        access.requireHost(jobHost, rule.get().permission());
                    }
                } else if (t.host != null && !t.host.isBlank() && (t.user == null || t.user.isBlank())) {
                    // Hôte seul (journal, audit, verrou) : grant sur l’hôte.
                    access.requireHost(t.host, rule.get().permission());
                } else if (t.host == null || t.host.isBlank()) {
                    // S4 : fail-closed — pas de serveur identifiable = admin plateforme seulement.
                    access.requireServerOrAdmin(rule.get().permission());
                } else {
                    access.requireServer(t.host, t.port, t.user, rule.get().permission());
                }
            } else {
                access.require(rule.get().permission());
            }
            chain.doFilter(effective, response);
        } catch (PlatformAccessException ex) {
            response.setStatus(ex.status());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"" + ex.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private Target resolveTarget(HttpServletRequest request, byte[] body) throws IOException {
        String host = request.getParameter("host");
        String user = request.getParameter("user");
        Integer port = parsePort(request.getParameter("port"));
        if (body != null && body.length > 0) {
            JsonNode node = mapper.readTree(body);
            if (node != null) {
                if (host == null || host.isBlank()) {
                    host = text(node, "host");
                }
                if (user == null || user.isBlank()) {
                    user = text(node, "user");
                }
                if (port == null && node.hasNonNull("port")) {
                    port = node.get("port").asInt(22);
                }
            }
        }
        return new Target(host, port == null ? 22 : port, user);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Target(String host, int port, String user) {}
}
