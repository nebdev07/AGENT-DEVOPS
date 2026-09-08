package com.nebdev.devopsagent.ssh;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class TrustedHostStore {

    private final Path file;
    private final ObjectMapper mapper;

    public TrustedHostStore(DevopsProperties properties, ObjectMapper mapper) {
        this.file = Path.of(properties.getStateDir(), "trusted-hosts.json");
        this.mapper = mapper;
    }

    public synchronized void trust(String host, int port, String fingerprint) throws IOException {
        Map<String, List<String>> all = load();
        String key = id(host, port);
        List<String> fps = new ArrayList<>(all.getOrDefault(key, List.of()));
        String fp = fingerprint.toLowerCase(Locale.ROOT);
        if (!fps.contains(fp)) {
            fps.add(fp);
        }
        all.put(key, fps);
        Files.createDirectories(file.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), all);
    }

    public synchronized boolean isTrusted(String host, int port, String fingerprint) {
        Map<String, List<String>> all = loadQuiet();
        String fp = fingerprint == null ? "" : fingerprint.toLowerCase(Locale.ROOT);
        return all.getOrDefault(id(host, port), List.of()).contains(fp)
                || all.getOrDefault(id(host, 22), List.of()).contains(fp);
    }

    public synchronized boolean hasHost(String host, int port) {
        Map<String, List<String>> all = loadQuiet();
        return all.containsKey(id(host, port));
    }

    private Map<String, List<String>> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        return mapper.readValue(file.toFile(), new TypeReference<>() {});
    }

    private Map<String, List<String>> loadQuiet() {
        try {
            return load();
        } catch (IOException e) {
            return Map.of();
        }
    }

    static String id(String host, int port) {
        return host.trim().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
