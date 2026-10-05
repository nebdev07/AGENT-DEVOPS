package com.nebdev.devopsagent.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Serveurs déjà analysés avec succès : de quoi rouvrir une session sans tout ressaisir.
 * Ne contient ni mot de passe ni clé ; la clé privée de l'agent reste celle de la configuration.
 */
@Component
public class KnownServerStore {

    static final String FILE_NAME = "known-servers.json";

    private final Path file;
    private final ObjectMapper mapper;

    public KnownServerStore(DevopsProperties properties, ObjectMapper mapper) {
        this.file = Path.of(properties.getStateDir()).resolve(FILE_NAME);
        this.mapper = mapper.copy()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public synchronized List<KnownServer> list() throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<KnownServer> all = mapper.readValue(file.toFile(), new TypeReference<List<KnownServer>>() {});
        return all.stream()
                .sorted(Comparator.comparing(KnownServer::lastAnalysedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public synchronized KnownServer remember(String name, String host, int port, String user, String os, Instant at)
            throws IOException {
        String h = normalizeHost(host);
        if (h.isEmpty() || user == null || user.isBlank()) {
            throw new IllegalArgumentException("Serveur incomplet : adresse et utilisateur SSH requis.");
        }
        KnownServer entry = new KnownServer(
                name == null ? "" : name.trim(), h, port <= 0 ? 22 : port, user.trim(),
                os == null || os.isBlank() ? "auto" : os.trim(), at);
        List<KnownServer> all = new ArrayList<>(list());
        all.removeIf(s -> s.host().equals(entry.host()) && s.port() == entry.port());
        all.add(0, entry);
        write(all);
        return entry;
    }

    public synchronized boolean forget(String host, int port) throws IOException {
        String h = normalizeHost(host);
        List<KnownServer> all = new ArrayList<>(list());
        boolean removed = all.removeIf(s -> s.host().equals(h) && s.port() == port);
        if (removed) {
            write(all);
        }
        return removed;
    }

    private void write(List<KnownServer> all) throws IOException {
        Files.createDirectories(file.getParent());
        mapper.writeValue(file.toFile(), all);
    }

    static String normalizeHost(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KnownServer(
            String name,
            String host,
            int port,
            String user,
            String os,
            Instant lastAnalysedAt
    ) {}
}
