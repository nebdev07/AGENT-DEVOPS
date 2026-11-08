package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.journal.JournalEntry;
import com.nebdev.devopsagent.journal.JournalExport;
import com.nebdev.devopsagent.journal.JournalStore;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.NotifyService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Journal des actions par serveur (lecture seule) et son export CSV / HTML. */
@RestController
@RequestMapping("/api/v1/journal")
public class JournalController {

    private final JournalStore store;
    private final NotifyService notifyService;
    private final ActionGuard actionGuard;
    private final com.nebdev.devopsagent.platform.AccessControl access;

    public JournalController(JournalStore store, NotifyService notifyService, ActionGuard actionGuard,
                             com.nebdev.devopsagent.platform.AccessControl access) {
        this.access = access;
        this.store = store;
        this.notifyService = notifyService;
        this.actionGuard = actionGuard;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "host", required = false) String host,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) throws IOException {
        // Compat : ancien ?limit= sans pagination
        if (limit != null && (date == null || date.isBlank()) && page <= 1) {
            List<JournalEntry> entries = store.list(host, Math.max(1, Math.min(limit, 1000)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("host", host == null ? "" : host.strip());
            out.put("key", JournalStore.key(host));
            out.put("entries", entries);
            out.put("total", entries.size());
            out.put("page", 1);
            out.put("pageSize", entries.size());
            out.put("totalPages", 1);
            out.put("date", "");
            out.put("dates", store.page(host, "", 1, 1).dates());
            out.put("journals", visibleKeys());
            return out;
        }
        JournalStore.Page p = store.page(host, date, page, pageSize);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", host == null ? "" : host.strip());
        out.put("key", JournalStore.key(host));
        out.put("entries", p.entries());
        out.put("total", p.total());
        out.put("page", p.page());
        out.put("pageSize", p.pageSize());
        out.put("totalPages", p.totalPages());
        out.put("date", p.date());
        out.put("dates", p.dates());
        out.put("journals", visibleKeys());
        return out;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(value = "host", required = false) String host,
            @RequestParam(value = "format", defaultValue = "html") String format,
            @RequestParam(value = "client", required = false) String client,
            @RequestParam(value = "date", required = false) String date
    ) throws IOException {
        List<JournalEntry> entries = store.list(host, 0);
        if (date != null && !date.isBlank()) {
            var day = JournalStore.parseDay(date);
            if (day != null) {
                entries = entries.stream().filter(e -> day.equals(JournalStore.dayOf(e.at()))).toList();
            }
        }
        String daySuffix = (date == null || date.isBlank()) ? "" : ("-" + date.strip());
        String base = "journal-" + JournalStore.key(host) + daySuffix + "-"
                + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC));
        String f = format.strip().toLowerCase(Locale.ROOT);
        String titleNote = (date == null || date.isBlank()) ? null : ("Journée " + date.strip() + " (UTC)");
        return switch (f) {
            case "csv" -> download(base + ".csv", new MediaType("text", "csv", StandardCharsets.UTF_8),
                    JournalExport.csv(entries));
            case "html" -> download(base + ".html", new MediaType("text", "html", StandardCharsets.UTF_8),
                    JournalExport.html(host, client == null || client.isBlank()
                            ? titleNote : client, entries));
            default -> throw new IllegalArgumentException("Format inconnu : " + format + " (csv ou html).");
        };
    }

    /**
     * Envoie un résumé d’activité (texte) aux destinataires des alertes e-mail du VPS.
     * CHANGE confirmé : utilise le SMTP déjà posé sur le serveur.
     */
    @PostMapping("/mail")
    public Map<String, Object> mail(@RequestBody MailRequest body) throws IOException {
        if (body == null || body.host() == null || body.host().isBlank()
                || body.user() == null || body.user().isBlank()) {
            throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH du serveur.");
        }
        actionGuard.assertConfirmedChange("test-notify", Boolean.TRUE.equals(body.confirm()));
        int port = body.port() == null ? 22 : body.port();
        SshTarget target = new SshTarget(body.host().trim(), port, body.user().trim());
        String date = body.date() == null ? "" : body.date().strip();
        List<JournalEntry> entries;
        if (!date.isBlank()) {
            entries = store.page(body.host(), date, 1, 1000).entries();
        } else {
            entries = store.list(body.host(), 80);
        }
        String client = body.client() == null ? "" : body.client().strip();
        String subject = client.isBlank()
                ? "Rapport d’activité — " + body.host().trim()
                : "Rapport d’activité — " + client;
        if (!date.isBlank()) {
            subject = subject + " (" + date + ")";
        }
        String text = JournalExport.plainSummary(body.host(), client, entries);
        var outcome = notifyService.announce(target, "activity", subject, text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", outcome.testOk() ? "ok" : "failed");
        out.put("sent", outcome.testOk());
        out.put("error", outcome.error() == null ? "" : outcome.error());
        out.put("entries", entries.size());
        out.put("date", date);
        return out;
    }

    public record MailRequest(String host, Integer port, String user, Boolean confirm, String client, String date) {}

    private static ResponseEntity<byte[]> download(String file, MediaType type, String body) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler(UnsafeActionException.class)
    public ResponseEntity<Map<String, String>> forbidden(UnsafeActionException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> io(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    /** S2 : la liste de tous les journaux n’est montrée qu’à l’admin plateforme (console locale : toujours). */
    private List<String> visibleKeys() throws IOException {
        return access.currentIsPlatformAdmin() ? store.keys() : List.of();
    }
}
