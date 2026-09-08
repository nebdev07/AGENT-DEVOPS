package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class StackIntentStore {

    private final Path file;
    private final ObjectMapper mapper;

    public StackIntentStore(DevopsProperties properties, ObjectMapper mapper) {
        this.file = Path.of(properties.getStateDir()).resolve("stack-intent.json");
        this.mapper = mapper;
    }

    public Set<String> load() throws IOException {
        return new LinkedHashSet<>(loadIntent().enabled());
    }

    public Intent loadIntent() throws IOException {
        if (!Files.isRegularFile(file)) {
            Set<String> enabled = StackPolicy.defaults();
            return new Intent(List.copyOf(enabled), StackPolicy.defaultDetails(enabled), catalogIds());
        }
        Intent raw = mapper.readValue(file.toFile(), new TypeReference<Intent>() {});
        if (raw == null || raw.enabled() == null) {
            Set<String> enabled = StackPolicy.defaults();
            return new Intent(List.copyOf(enabled), StackPolicy.defaultDetails(enabled), catalogIds());
        }
        Intent merged = mergeNewDefaults(raw);
        if (!sameIntent(raw, merged)) {
            persist(merged);
        }
        return merged;
    }

    public void save(Set<String> enabled) throws IOException {
        save(enabled, null);
    }

    public void save(Set<String> enabled, Map<String, List<String>> details) throws IOException {
        StackPolicy.Evaluation eval = StackPolicy.evaluate(enabled, details);
        persist(new Intent(eval.enabled(), eval.details(), catalogIds()));
    }

    static Intent mergeNewDefaults(Intent raw) {
        Set<String> enabled = new LinkedHashSet<>(raw.enabled());
        Map<String, List<String>> details = raw.details() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(raw.details());
        Set<String> seen = new LinkedHashSet<>();
        if (raw.catalogSeen() != null && !raw.catalogSeen().isEmpty()) {
            seen.addAll(raw.catalogSeen());
        } else {
            seen.addAll(enabled);
            seen.addAll(details.keySet());
        }
        for (StackComponent c : StackCatalog.all()) {
            boolean firstTime = seen.add(c.id());
            if (firstTime && c.defaultEnabled()) {
                enabled.add(c.id());
            }
        }
        return new Intent(List.copyOf(enabled), StackPolicy.normalizeDetails(enabled, details), List.copyOf(seen));
    }

    private void persist(Intent intent) throws IOException {
        Files.createDirectories(file.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), intent);
    }

    private static List<String> catalogIds() {
        return StackCatalog.all().stream().map(StackComponent::id).toList();
    }

    private static boolean sameIntent(Intent a, Intent b) {
        return a.enabled().equals(b.enabled())
                && Objects.equals(a.details(), b.details())
                && new LinkedHashSet<>(a.catalogSeen()).equals(new LinkedHashSet<>(b.catalogSeen()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Intent(List<String> enabled, Map<String, List<String>> details, List<String> catalogSeen) {
        public Intent {
            enabled = enabled == null ? List.of() : List.copyOf(enabled);
            details = details == null ? Map.of() : new LinkedHashMap<>(details);
            catalogSeen = catalogSeen == null ? List.of() : List.copyOf(catalogSeen);
        }

        public Intent(List<String> enabled, Map<String, List<String>> details) {
            this(enabled, details, List.of());
        }
    }
}
