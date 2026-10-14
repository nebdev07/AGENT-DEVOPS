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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    /** Les entrées les plus récentes d’abord ; {@code limit <= 0} = tout. */
    public synchronized List<JournalEntry> list(String host, int limit) throws IOException {
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
                // ligne tronquée (arrêt brutal pendant l’écriture) : les autres restent lisibles
            }
        }
        Collections.reverse(out);
        return limit > 0 && out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
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
