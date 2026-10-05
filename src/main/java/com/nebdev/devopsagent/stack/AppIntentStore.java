package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class AppIntentStore {

    private final Path file;
    private final ObjectMapper mapper;

    public AppIntentStore(DevopsProperties properties, ObjectMapper mapper) {
        this.file = Path.of(properties.getStateDir()).resolve("apps-intent.json");
        this.mapper = mapper;
    }

    public List<AppSpec> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        Intent raw = mapper.readValue(file.toFile(), new TypeReference<Intent>() {});
        if (raw == null || raw.apps() == null) {
            return List.of();
        }
        List<AppSpec> out = new ArrayList<>();
        for (AppSpec a : raw.apps()) {
            out.add(AppSpec.normalized(a.id(), a.gitUrl(), a.gitRef(), a.hostname(), a.port()));
        }
        return List.copyOf(out);
    }

    public AppSpec require(String id) throws IOException {
        String slug = id == null ? "" : id.trim().toLowerCase();
        return load().stream()
                .filter(a -> a.id().equals(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(OperatorMessage.of(
                        "aucune app enregistrée avec l’id « " + slug + " ».",
                        "Étape Déployer — liste Applications",
                        "remplis le formulaire puis clique « Enregistrer dans la liste » avant Déployer.")));
    }

    public List<AppSpec> save(List<AppSpec> apps) throws IOException {
        if (apps != null && apps.size() > 20) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "limite de 20 apps atteinte.",
                    "Bande Applications — Enregistrer",
                    "retire une app de la liste avant d’en ajouter une autre."));
        }
        List<AppSpec> clean = new ArrayList<>();
        if (apps != null) {
            java.util.LinkedHashMap<String, AppSpec> byId = new java.util.LinkedHashMap<>();
            java.util.HashSet<String> hosts = new java.util.HashSet<>();
            for (AppSpec a : apps) {
                AppSpec n = AppSpec.normalized(a.id(), a.gitUrl(), a.gitRef(), a.hostname(), a.port());
                if (!hosts.add(n.hostname())) {
                    throw new IllegalArgumentException(OperatorMessage.of(
                            "le nom d’hôte « " + n.hostname() + " » est déjà pris par une autre app de la liste.",
                            "Champ Nom d’hôte — bande Applications",
                            "chaque app doit avoir son DNS. Ex. mon-app.<IP>.sslip.io — pas le même hôte que vps-autopilot."));
                }
                byId.put(n.id(), n);
            }
            clean.addAll(byId.values());
        }
        Files.createDirectories(file.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), new Intent(clean));
        return List.copyOf(clean);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Intent(List<AppSpec> apps) {
        public Intent {
            apps = apps == null ? List.of() : List.copyOf(apps);
        }
    }
}
