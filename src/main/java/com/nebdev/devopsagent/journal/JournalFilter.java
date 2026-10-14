package com.nebdev.devopsagent.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Journalise chaque action de la console (voir {@link ActionCatalog}) après exécution.
 * Seuls des champs sûrs sont retenus ; le corps brut (SQL, mot de passe) n’est jamais écrit.
 */
@Component
public class JournalFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JournalFilter.class);

    /** Champs de requête recopiés dans le journal. */
    static final List<String> REQUEST_FIELDS = List.of("id", "platformId", "service", "name", "component", "file", "repo",
            "confirm", "rotate");
    /** Champs de réponse recopiés dans le journal. */
    static final List<String> RESPONSE_FIELDS = List.of("file", "risk");
    private static final int MAX_VALUE = 200;
    private static final int MAX_ERROR = 400;

    private final JournalStore store;
    private final ObjectMapper json = new ObjectMapper();

    public JournalFilter(JournalStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ActionCatalog.classify(request.getMethod(), request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ActionCatalog.Action action = ActionCatalog.classify(request.getMethod(), request.getRequestURI());
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        long start = System.nanoTime();
        Instant at = Instant.now();
        boolean recorded = false;
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException | RuntimeException e) {
            record(action, req, res, at, start, String.valueOf(e.getMessage()));
            recorded = true;
            throw e;
        } finally {
            if (!recorded) {
                record(action, req, res, at, start, null);
            }
            res.copyBodyToResponse();
        }
    }

    private void record(ActionCatalog.Action action, ContentCachingRequestWrapper req,
                        ContentCachingResponseWrapper res, Instant at, long start, String exception) {
        try {
            JsonNode body = parse(req.getContentAsByteArray(), req.getContentType());
            JsonNode answer = parse(res.getContentAsByteArray(), res.getContentType());
            int status = exception != null ? 500 : res.getStatus();
            boolean ok = exception == null && status >= 200 && status < 300;
            Map<String, String> details = new LinkedHashMap<>();
            for (String f : REQUEST_FIELDS) {
                put(details, f, field(body, req, f));
            }
            if (ok) {
                for (String f : RESPONSE_FIELDS) {
                    put(details, f, text(answer, f));
                }
            }
            String error = ok ? null : clip(exception != null ? exception : text(answer, "error"), MAX_ERROR);
            store.append(new JournalEntry(
                    at.toString(),
                    System.getProperty("user.name", ""),
                    blankToEmpty(field(body, req, "host")),
                    blankToEmpty(field(body, req, "user")),
                    req.getMethod(),
                    req.getRequestURI(),
                    action.label(),
                    action.risk().name(),
                    ok ? JournalEntry.OK : JournalEntry.FAILED,
                    status,
                    (System.nanoTime() - start) / 1_000_000,
                    details,
                    error
            ));
        } catch (IOException | RuntimeException e) {
            log.warn("Journal non écrit pour {} {} : {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        }
    }

    private JsonNode parse(byte[] content, String contentType) {
        if (content == null || content.length == 0 || contentType == null || !contentType.contains("json")) {
            return null;
        }
        try {
            return json.readTree(content);
        } catch (IOException e) {
            return null;
        }
    }

    private static String field(JsonNode body, HttpServletRequest req, String name) {
        String v = text(body, name);
        return v != null ? v : req.getParameter(name);
    }

    static String text(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode v = node.get(name);
        return v == null || v.isNull() || v.isContainerNode() ? null : v.asText();
    }

    private static void put(Map<String, String> details, String key, String value) {
        if (value != null && !value.isBlank()) {
            details.put(key, clip(value.strip(), MAX_VALUE));
        }
    }

    private static String clip(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static String blankToEmpty(String v) {
        return v == null ? "" : v.strip();
    }
}
