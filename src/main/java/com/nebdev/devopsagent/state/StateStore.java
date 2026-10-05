package com.nebdev.devopsagent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nebdev.devopsagent.config.DevopsProperties;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class StateStore {

    private static final Set<String> NOT_SNAPSHOTS = Set.of(
            "stack-intent.json", "trusted-hosts.json", "apps-intent.json",
            "platforms-intent.json", "public-domain.json", "fixture.json", KnownServerStore.FILE_NAME);

    private final Path dir;
    private final ObjectMapper mapper;

    public StateStore(DevopsProperties properties, ObjectMapper mapper) {
        this.dir = Path.of(properties.getStateDir());
        this.mapper = mapper.copy()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Path save(ServerSnapshot snapshot) throws IOException {
        Files.createDirectories(dir);
        String safe = sanitize(snapshot.host());
        Path file = dir.resolve(safe + ".json");
        InfrastructureState state = InfrastructureState.from(snapshot);
        mapper.writeValue(file.toFile(), state);
        return file;
    }

    public InfrastructureState loadLatest() throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path latest = null;
        long best = Long.MIN_VALUE;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".json") && !NOT_SNAPSHOTS.contains(name);
            }).toList()) {
                long m = Files.getLastModifiedTime(p).toMillis();
                if (m >= best) {
                    best = m;
                    latest = p;
                }
            }
        }
        if (latest == null) {
            return null;
        }
        return mapper.readValue(latest.toFile(), InfrastructureState.class);
    }

    public InfrastructureState load(String host) throws IOException {
        if (host == null || host.isBlank()) {
            return null;
        }
        Path file = dir.resolve(sanitize(host.trim()) + ".json");
        if (!Files.isRegularFile(file) || NOT_SNAPSHOTS.contains(file.getFileName().toString())) {
            return null;
        }
        return mapper.readValue(file.toFile(), InfrastructureState.class);
    }

    static String sanitize(String host) {
        if (host == null || host.isBlank()) {
            return "unknown";
        }
        return host.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public record InfrastructureState(
            String schema,
            ServerSnapshot lastDiscovery,
            ServerFlags server,
            List<String> projects
    ) {
        static InfrastructureState from(ServerSnapshot snap) {
            boolean docker = snap.docker() != null && snap.docker().present();
            boolean traefik = snap.traefik() != null && snap.traefik().present();
            boolean mariadb = snap.mariadb() != null && snap.mariadb().present();
            boolean redis = snap.redis() != null && snap.redis().present();
            String os = snap.osId() == null || snap.osId().isBlank() ? "unknown" : snap.osId().toLowerCase(Locale.ROOT);
            return new InfrastructureState(
                    "v0",
                    snap,
                    new ServerFlags(os, docker, traefik, mariadb, redis),
                    List.of()
            );
        }
    }

    public record ServerFlags(
            String os,
            boolean docker,
            boolean traefik,
            boolean mariadb,
            boolean redis
    ) {}
}
