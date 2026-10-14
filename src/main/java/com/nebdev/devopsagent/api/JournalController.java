package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.journal.JournalEntry;
import com.nebdev.devopsagent.journal.JournalExport;
import com.nebdev.devopsagent.journal.JournalStore;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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

    public JournalController(JournalStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "host", required = false) String host,
            @RequestParam(value = "limit", defaultValue = "100") int limit
    ) throws IOException {
        List<JournalEntry> entries = store.list(host, Math.max(1, Math.min(limit, 1000)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", host == null ? "" : host.strip());
        out.put("key", JournalStore.key(host));
        out.put("entries", entries);
        out.put("journals", store.keys());
        return out;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(value = "host", required = false) String host,
            @RequestParam(value = "format", defaultValue = "html") String format,
            @RequestParam(value = "client", required = false) String client
    ) throws IOException {
        List<JournalEntry> entries = store.list(host, 0);
        String base = "journal-" + JournalStore.key(host) + "-"
                + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC));
        String f = format.strip().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "csv" -> download(base + ".csv", new MediaType("text", "csv", StandardCharsets.UTF_8),
                    JournalExport.csv(entries));
            case "html" -> download(base + ".html", new MediaType("text", "html", StandardCharsets.UTF_8),
                    JournalExport.html(host, client, entries));
            default -> throw new IllegalArgumentException("Format inconnu : " + format + " (csv ou html).");
        };
    }

    private static ResponseEntity<byte[]> download(String file, MediaType type, String body) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
