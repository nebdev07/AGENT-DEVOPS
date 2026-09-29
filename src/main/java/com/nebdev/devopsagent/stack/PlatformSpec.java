package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Produit multi-services (ADR-036 / V7). Intention locale ; pas d’executor dans V7-01.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformSpec(
        String id,
        String baseDomain,
        String routing,
        List<PlatformServiceSpec> services
) {

    public static final String ROUTING_HOST = "host";

    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    public static PlatformSpec normalized(
            String id,
            String baseDomain,
            String routing,
            List<PlatformServiceSpec> services,
            String vpsIp
    ) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "aucun identifiant de plateforme.",
                    "Bande Plateformes — Identifiant",
                    "slug court, ex. ma-asso (pas un nom de domaine)."));
        }
        String slug = id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "identifiant « " + id.trim() + " » invalide.",
                    "Bande Plateformes — Identifiant",
                    "a-z, chiffres, tirets ; commence par une lettre ; max 32 car."));
        }
        String domain = PublicDomainStore.normalize(baseDomain == null ? "" : baseDomain);
        String route = routing == null || routing.isBlank() ? ROUTING_HOST : routing.trim().toLowerCase(Locale.ROOT);
        if ("path".equals(route) || "prefix".equals(route)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "le routage par chemin n’est pas encore disponible.",
                    "Bande Plateformes — routage",
                    "jour 1 = sous-domaines (www / api / admin). Les chemins /api viennent en V7-04."));
        }
        if (!ROUTING_HOST.equals(route)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "routage « " + route + " » inconnu.",
                    "Bande Plateformes — routage",
                    "laisse « host » (sous-domaines)."));
        }
        if (services == null || services.isEmpty()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "une plateforme doit avoir au moins un service.",
                    "Bande Plateformes — services",
                    "ajoute API, site, admin… chacun un Git HTTPS."));
        }
        if (services.size() > 12) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "plus de 12 services.",
                    "Bande Plateformes — services",
                    "retire un service avant d’en ajouter."));
        }
        List<PlatformServiceSpec> clean = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        HashSet<String> hosts = new HashSet<>();
        for (PlatformServiceSpec raw : services) {
            PlatformServiceSpec n = PlatformServiceSpec.normalized(
                    slug,
                    domain,
                    vpsIp,
                    raw.id(),
                    raw.gitUrl(),
                    raw.gitRef(),
                    raw.hostname(),
                    raw.port(),
                    raw.context(),
                    raw.needs(),
                    raw.buildArgs()
            );
            if (!ids.add(n.id())) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "deux services ont l’id « " + n.id() + " ».",
                        "Bande Plateformes — Identifiant de service",
                        "chaque service un id distinct (api, www, admin)."));
            }
            if (!hosts.add(n.hostname())) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "hôte « " + n.hostname() + " » utilisé deux fois dans cette plateforme.",
                        "Bande Plateformes — Nom d’hôte",
                        "un DNS par service."));
            }
            clean.add(n);
        }
        return new PlatformSpec(slug, domain, ROUTING_HOST, List.copyOf(clean));
    }
}
