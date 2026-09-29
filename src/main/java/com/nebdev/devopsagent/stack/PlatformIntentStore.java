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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@Component
public class PlatformIntentStore {

    private final Path file;
    private final ObjectMapper mapper;
    private final AppIntentStore appIntentStore;

    public PlatformIntentStore(DevopsProperties properties, ObjectMapper mapper, AppIntentStore appIntentStore) {
        this.file = Path.of(properties.getStateDir()).resolve("platforms-intent.json");
        this.mapper = mapper;
        this.appIntentStore = appIntentStore;
    }

    public List<PlatformSpec> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        Intent raw = mapper.readValue(file.toFile(), new TypeReference<Intent>() {});
        if (raw == null || raw.platforms() == null) {
            return List.of();
        }
        List<PlatformSpec> out = new ArrayList<>();
        for (PlatformSpec p : raw.platforms()) {
            out.add(PlatformSpec.normalized(p.id(), p.baseDomain(), p.routing(), p.services(), null));
        }
        return List.copyOf(out);
    }

    public List<PlatformSpec> save(List<PlatformSpec> platforms, String vpsIp) throws IOException {
        if (platforms != null && platforms.size() > 10) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "limite de 10 plateformes.",
                    "Bande Plateformes — Enregistrer",
                    "supprime une plateforme de la liste avant d’en ajouter."));
        }
        LinkedHashMap<String, PlatformSpec> byId = new LinkedHashMap<>();
        HashSet<String> hosts = new HashSet<>();
        for (AppSpec app : appIntentStore.load()) {
            hosts.add(app.hostname());
        }
        if (platforms != null) {
            for (PlatformSpec p : platforms) {
                PlatformSpec n = PlatformSpec.normalized(p.id(), p.baseDomain(), p.routing(), p.services(), vpsIp);
                for (PlatformServiceSpec s : n.services()) {
                    if (n.pathRouting()) {
                        if (!hosts.add(s.hostname())) {
                            throw new IllegalArgumentException(OperatorMessage.of(
                                    "l’hôte « " + s.hostname() + " » est déjà pris (app 1:1 ou autre plateforme).",
                                    "Bande Plateformes — Nom d’hôte",
                                    "en chemins, un DNS pour tout le produit ; il ne doit pas coller une app 1:1."));
                        }
                        break;
                    }
                    if (!hosts.add(s.hostname())) {
                        throw new IllegalArgumentException(OperatorMessage.of(
                                "l’hôte « " + s.hostname() + " » est déjà pris (app 1:1 ou autre plateforme).",
                                "Bande Plateformes — Nom d’hôte",
                                "chaque service et chaque app simple doit avoir son DNS — ou routage chemins."));
                    }
                }
                byId.put(n.id(), n);
            }
        }
        List<PlatformSpec> clean = new ArrayList<>(byId.values());
        Files.createDirectories(file.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), new Intent(clean));
        return List.copyOf(clean);
    }

    public List<PlatformSpec> delete(String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "aucun id fourni pour la suppression.",
                    "Liste Plateformes — Supprimer",
                    "indique l’identifiant (ex. ma-asso)."));
        }
        String slug = id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        List<PlatformSpec> current = load();
        boolean found = current.stream().anyMatch(p -> p.id().equals(slug));
        if (!found) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "aucune plateforme « " + slug + " » dans la liste.",
                    "Liste Plateformes — Supprimer",
                    "recharge la page si la liste a changé."));
        }
        List<PlatformSpec> kept = current.stream().filter(p -> !p.id().equals(slug)).toList();
        return save(kept, null);
    }

    public PlatformSpec require(String id) throws IOException {
        String slug = id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return load().stream()
                .filter(p -> p.id().equals(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(OperatorMessage.of(
                        "aucune plateforme « " + slug + " » enregistrée.",
                        "Bande Plateformes — Déployer",
                        "Enregistrer la plateforme avant Déployer.")));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Intent(List<PlatformSpec> platforms) {
        public Intent {
            platforms = platforms == null ? List.of() : List.copyOf(platforms);
        }
    }
}
