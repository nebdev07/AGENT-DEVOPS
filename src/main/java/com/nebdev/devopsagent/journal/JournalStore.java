package com.nebdev.devopsagent.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Journal append-only : {@code state/journal/<serveur>.jsonl}, une ligne JSON par action.
 * Les réglages faits sans serveur désigné vont dans {@code _poste.jsonl}.
 */
@Component
public class JournalStore {

    public static final String LOCAL_KEY = "_poste";

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public JournalStore(DevopsProperties properties) {
        this(Path.of(properties.getStateDir(), "journal"));
    }

    JournalStore(Path dir) {
        this.dir = dir;
    }

    public synchronized void append(JournalEntry entry) throws IOException {
        Files.createDirectories(dir);
        String line = json.writeValueAsString(entry) + "\n";
        Files.writeString(file(entry.host()), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Page de journal (plus récentes d’abord), filtrable par jour UTC. */
    public record Page(
            List<JournalEntry> entries,
            int total,
            int page,
            int pageSize,
            int totalPages,
            String date,
            List<String> dates
    ) {}

    /** Les entrées les plus récentes d’abord ; {@code limit <= 0} = tout. */
    public synchronized List<JournalEntry> list(String host, int limit) throws IOException {
        List<JournalEntry> out = readNewestFirst(host);
        return limit > 0 && out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    /**
     * @param date jour UTC {@code YYYY-MM-DD} (vide = tous)
     * @param page 1-indexé
     * @param pageSize 1–100
     */
    public synchronized Page page(String host, String date, int page, int pageSize) throws IOException {
        List<JournalEntry> all = readNewestFirst(host);
        List<String> dates = distinctDays(all);
        LocalDate day = parseDay(date);
        List<JournalEntry> filtered = day == null ? all : all.stream().filter(e -> day.equals(dayOf(e.at()))).toList();
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int total = filtered.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) size));
        int p = Math.min(Math.max(1, page), totalPages);
        int from = (p - 1) * size;
        List<JournalEntry> slice = from >= total ? List.of() : filtered.subList(from, Math.min(from + size, total));
        return new Page(List.copyOf(slice), total, p, size, totalPages, day == null ? "" : day.toString(), dates);
    }

    private List<JournalEntry> readNewestFirst(String host) throws IOException {
        Path f = file(host);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        List<JournalEntry> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(json.readValue(line, JournalEntry.class));
            } catch (IOException ignored) {
                // ligne tronquée : les autres restent lisibles
            }
        }
        Collections.reverse(out);
        return out;
    }

    public static LocalDate dayOf(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(iso.substring(0, Math.min(10, iso.length())));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public static LocalDate parseDay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static List<String> distinctDays(List<JournalEntry> entries) {
        Set<String> days = new LinkedHashSet<>();
        for (JournalEntry e : entries) {
            LocalDate d = dayOf(e.at());
            if (d != null) {
                days.add(d.toString());
            }
        }
        return List.copyOf(days);
    }

    /** Serveurs qui ont un journal (clé de fichier), {@code _poste} inclus. */
    public List<String> keys() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jsonl"))
                    .map(n -> n.substring(0, n.length() - ".jsonl".length()))
                    .sorted()
                    .toList();
        }
    }

    Path file(String host) {
        return dir.resolve(key(host) + ".jsonl");
    }

    public static String key(String host) {
        if (host == null || host.isBlank()) {
            return LOCAL_KEY;
        }
        String k = host.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return k.matches("^\\.*$") ? LOCAL_KEY : k;
    }
}
