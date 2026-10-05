package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Domaine de base public pour des liens mémorisables (ADR-033).
 * Ex. base = apps.exemple.com → mon-app.apps.exemple.com
 */
@Component
public class PublicDomainStore {

    private static final Pattern BASE = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    private final Path file;
    private final ObjectMapper mapper;

    public PublicDomainStore(DevopsProperties properties, ObjectMapper mapper) {
        this.file = Path.of(properties.getStateDir()).resolve("public-domain.json");
        this.mapper = mapper;
    }

    public String load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        State s = mapper.readValue(file.toFile(), State.class);
        return s == null || s.baseDomain() == null ? "" : s.baseDomain().trim().toLowerCase(Locale.ROOT);
    }

    public String save(String raw) throws IOException {
        String base = normalize(raw);
        Files.createDirectories(file.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), new State(base));
        return base;
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String b = raw.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://", "")
                .replaceAll("/$", "");
        if (b.startsWith("*.")) {
            b = b.substring(2);
        }
        if (b.isEmpty()) {
            return "";
        }
        if (!BASE.matcher(b).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "domaine de base « " + raw.trim() + " » invalide.",
                    "Champ Domaine public (Applications)",
                    "ex. apps.mondomaine.com (sans http://). DNS wildcard *.apps.mondomaine.com → IP du VPS."));
        }
        return b;
    }

    public static String suggestHost(String slug, String baseDomain, String vpsIp) {
        String id = slug == null ? "app" : slug.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
        if (id.isEmpty()) {
            id = "app";
        }
        if (baseDomain != null && !baseDomain.isBlank()) {
            return id + "." + baseDomain.trim().toLowerCase(Locale.ROOT);
        }
        if (vpsIp != null && vpsIp.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return id + "." + vpsIp + ".sslip.io";
        }
        return "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record State(String baseDomain) {}
}
