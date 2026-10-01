package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Produit multi-services (ADR-036 / V7).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformSpec(
        String id,
        String baseDomain,
        String routing,
        List<PlatformServiceSpec> services
) {

    public static final String ROUTING_HOST = "host";
    public static final String ROUTING_PATH = "path";

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
        if ("prefix".equals(route)) {
            route = ROUTING_PATH;
        }
        if (!ROUTING_HOST.equals(route) && !ROUTING_PATH.equals(route)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "routage « " + route + " » inconnu.",
                    "Bande Plateformes — routage",
                    "sous-domaines (host) ou chemins (path) sur un seul DNS."));
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
        boolean pathRouting = ROUTING_PATH.equals(route);
        String sharedHost = null;
        if (pathRouting) {
            sharedHost = services.stream()
                    .map(PlatformServiceSpec::hostname)
                    .filter(h -> h != null && !h.isBlank())
                    .map(h -> h.trim().toLowerCase(Locale.ROOT))
                    .findFirst()
                    .orElseGet(() -> PlatformServiceSpec.derivePlatformHost(slug, domain, vpsIp));
        }
        List<PlatformServiceSpec> clean = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        HashSet<String> hosts = new HashSet<>();
        HashSet<String> paths = new HashSet<>();
        for (PlatformServiceSpec raw : services) {
            if (pathRouting && raw.hostname() != null && !raw.hostname().isBlank()) {
                String given = raw.hostname().trim().toLowerCase(Locale.ROOT);
                if (!given.equals(sharedHost)) {
                    throw new IllegalArgumentException(OperatorMessage.of(
                            "en routage chemins, tous les services partagent un seul DNS.",
                            "Bande Plateformes — Nom d’hôte",
                            "un hôte pour le produit (ex. ma-asso.apps.exemple.com), des chemins /api /admin."));
                }
            }
            PlatformServiceSpec n = PlatformServiceSpec.normalized(
                    slug,
                    domain,
                    vpsIp,
                    raw.id(),
                    raw.gitUrl(),
                    raw.gitRef(),
                    pathRouting ? sharedHost : raw.hostname(),
                    raw.port(),
                    raw.context(),
                    raw.needs(),
                    raw.buildArgs(),
                    raw.pathPrefix(),
                    pathRouting,
                    raw.template(),
                    raw.env(),
                    raw.keepPath()
            );
            if (PlatformCompose.CACHE_SERVICE.equals(n.id())) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "l’id « " + n.id() + " » est réservé au Redis privé de la plateforme.",
                        "Bande Plateformes — Identifiant de service",
                        "choisis un autre id (api, www, admin)."));
            }
            if (!ids.add(n.id())) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "deux services ont l’id « " + n.id() + " ».",
                        "Bande Plateformes — Identifiant de service",
                        "chaque service un id distinct (api, www, admin)."));
            }
            if (pathRouting) {
                if (!paths.add(n.pathPrefix())) {
                    throw new IllegalArgumentException(OperatorMessage.of(
                            "chemin « " + n.pathPrefix() + " » utilisé deux fois.",
                            "Bande Plateformes — Chemin URL",
                            "un chemin par service, ex. / et /api."));
                }
            } else if (!hosts.add(n.hostname())) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "hôte « " + n.hostname() + " » utilisé deux fois dans cette plateforme.",
                        "Bande Plateformes — Nom d’hôte",
                        "un DNS par service, ou passe en routage chemins."));
            }
            clean.add(n);
        }
        return new PlatformSpec(slug, domain, route, List.copyOf(clean));
    }

    public boolean pathRouting() {
        return ROUTING_PATH.equals(routing);
    }

    public PlatformSpec masked() {
        return new PlatformSpec(id, baseDomain, routing,
                services.stream().map(PlatformServiceSpec::masked).toList());
    }
}
