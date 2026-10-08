package com.nebdev.devopsagent.stack;

import java.util.regex.Pattern;

/**
 * Compose déterministe pour une plateforme (ADR-036). Aucun port publié.
 */
final class PlatformCompose {

    static final String CACHE_SERVICE = "platform-cache";
    static final String TEMPLATE_DOCKERFILE = "Dockerfile.autopilot";

    private PlatformCompose() {
    }

    static boolean needsRedisPrivate(PlatformSpec platform) {
        return platform.services().stream().anyMatch(PlatformCompose::needsRedisPrivate);
    }

    static boolean needsRedisPrivate(PlatformServiceSpec s) {
        return s.needs().contains(PlatformServiceSpec.NEED_REDIS_PRIVATE);
    }

    static boolean needsData(PlatformSpec platform) {
        return platform.services().stream().anyMatch(PlatformCompose::needsData);
    }

    static boolean needsData(PlatformServiceSpec s) {
        return s.needs().contains("mariadb") || s.needs().contains("redis");
    }

    static boolean needsDb(PlatformSpec platform) {
        return platform.services().stream().anyMatch(s -> s.needs().contains("mariadb"));
    }

    static boolean needsRedis(PlatformSpec platform) {
        return platform.services().stream().anyMatch(s -> s.needs().contains("redis"));
    }

    static boolean needsUploads(PlatformServiceSpec s) {
        return s.needs().stream().anyMatch(n -> n.startsWith("volume:"));
    }

    /** Nom Docker fixe (sans préfixe de projet compose) : la restauration y écrit avant le premier déploiement. */
    static String uploadsVolume(PlatformSpec platform) {
        return "plat_" + platform.id().replace('-', '_') + "_uploads";
    }

    static boolean anyUploads(PlatformSpec platform) {
        return platform.services().stream().anyMatch(PlatformCompose::needsUploads);
    }

    static String routerName(PlatformSpec platform, PlatformServiceSpec s) {
        return platform.id() + "-" + s.id();
    }

    static String yaml(PlatformSpec platform) {
        return yaml(platform, ServerProfile.DEFAULT);
    }

    static String yaml(PlatformSpec platform, ServerProfile profile) {
        boolean data = needsData(platform);
        boolean uploads = platform.services().stream().anyMatch(PlatformCompose::needsUploads);
        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");
        for (PlatformServiceSpec s : platform.services()) {
            String router = routerName(platform, s);
            String ctx = "./src/" + s.id();
            if (!s.context().isBlank()) {
                ctx = ctx + "/" + s.context();
            }
            sb.append("  ").append(s.id()).append(":\n");
            sb.append("    build:\n");
            sb.append("      context: ").append(ctx).append("\n");
            if (s.templated()) {
                sb.append("      dockerfile: ").append(TEMPLATE_DOCKERFILE).append("\n");
            }
            if (!s.buildArgs().isEmpty()) {
                sb.append("      args:\n");
                s.buildArgs().forEach((k, v) -> sb.append("        ").append(k).append(": ")
                        .append(yamlQuote(v)).append("\n"));
            }
            sb.append("    container_name: plat-").append(platform.id()).append("-").append(s.id()).append("\n");
            sb.append("    restart: unless-stopped\n");
            sb.append("    env_file:\n");
            sb.append("      - ./svc-").append(s.id()).append(".env\n");
            if (s.needs().contains("mariadb")) {
                sb.append("      - ./db.env\n");
            }
            sb.append("      - ./svc-").append(s.id()).append(".local.env\n");
            if (needsRedisPrivate(s)) {
                sb.append("    depends_on:\n");
                sb.append("      - ").append(CACHE_SERVICE).append("\n");
            }
            if (needsUploads(s)) {
                sb.append("    volumes:\n");
                sb.append("      - ").append(uploadsVolume(platform)).append(":/app/data\n");
            }
            sb.append("    networks:\n");
            sb.append("      - proxy\n");
            if (needsData(s) && data) {
                sb.append("      - data\n");
            }
            if (needsRedisPrivate(s)) {
                sb.append("      - internal\n");
            }
            sb.append("    labels:\n");
            sb.append("      - traefik.enable=true\n");
            sb.append("      - traefik.docker.network=proxy\n");
            sb.append("      - vps.autopilot.platform=").append(platform.id()).append("\n");
            sb.append("      - vps.autopilot.service=").append(s.id()).append("\n");
            appendRouterLabels(sb, router, s.hostname(), s.pathPrefix(), platform.pathRouting(),
                    s.stripPath(platform.pathRouting()), !profile.httpOnly());
            if (s == aliasCarrier(platform)) {
                appendAliasRedirect(sb, platform, s.hostname(), profile);
            }
            sb.append("      - traefik.http.services.").append(router)
                    .append(".loadbalancer.server.port=").append(s.port()).append("\n");
        }
        boolean cache = needsRedisPrivate(platform);
        if (cache) {
            sb.append("  ").append(CACHE_SERVICE).append(":\n");
            sb.append("    image: redis:7.4-alpine\n");
            sb.append("    container_name: plat-").append(platform.id()).append("-cache\n");
            sb.append("    restart: unless-stopped\n");
            sb.append("    command: [\"redis-server\", \"--save\", \"\", \"--appendonly\", \"no\"]\n");
            sb.append("    networks:\n");
            sb.append("      - internal\n");
        }
        if (uploads) {
            sb.append("volumes:\n");
            sb.append("  ").append(uploadsVolume(platform)).append(":\n");
            sb.append("    name: ").append(uploadsVolume(platform)).append("\n");
        }
        sb.append("networks:\n");
        sb.append("  proxy:\n");
        sb.append("    external: true\n");
        if (data) {
            sb.append("  data:\n");
            sb.append("    external: true\n");
        }
        if (cache) {
            sb.append("  internal:\n");
            sb.append("    internal: true\n");
        }
        return sb.toString();
    }

    static String publicUrl(PlatformSpec platform, PlatformServiceSpec s) {
        return publicUrl(platform, s, ServerProfile.DEFAULT);
    }

    static String publicUrl(PlatformSpec platform, PlatformServiceSpec s, ServerProfile profile) {
        String base = profile.scheme() + "://" + profile.authority(s.hostname());
        if (platform.pathRouting() && s.pathPrefix() != null && !s.pathPrefix().isBlank()
                && !"/".equals(s.pathPrefix())) {
            return base + s.pathPrefix() + "/";
        }
        return base + "/";
    }

    static String manifest(PlatformSpec platform) {
        StringBuilder sb = new StringBuilder();
        for (PlatformServiceSpec s : platform.services()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s.id()).append('\t')
                    .append(s.gitUrl()).append('\t')
                    .append(s.gitRef()).append('\t')
                    .append(dash(s.context())).append('\t')
                    .append(dash(s.template())).append('\t')
                    .append(dash(GitUrls.ssh(s.gitUrl()) ? GitDeployKeyStore.keyId(s.gitUrl()) : ""));
        }
        return sb.toString();
    }

    /** bash {@code read} fusionne les tabulations consécutives : jamais de colonne vide. */
    private static String dash(String v) {
        return v == null || v.isBlank() ? "-" : v;
    }

    private static void appendRouterLabels(
            StringBuilder sb,
            String router,
            String hostname,
            String pathPrefix,
            boolean pathRouting,
            boolean strip,
            boolean tls
    ) {
        boolean prefixed = pathRouting && pathPrefix != null && !pathPrefix.isBlank() && !"/".equals(pathPrefix);
        String rule = routerRule(hostname, prefixed ? pathPrefix : null);
        sb.append("      - \"traefik.http.routers.").append(router).append(".rule=").append(rule).append("\"\n");
        sb.append("      - traefik.http.routers.").append(router).append(".entrypoints=web\n");
        if (tls) {
            sb.append("      - \"traefik.http.routers.").append(router).append("-secure.rule=").append(rule).append("\"\n");
            sb.append("      - traefik.http.routers.").append(router).append("-secure.entrypoints=websecure\n");
            sb.append("      - traefik.http.routers.").append(router).append("-secure.tls=true\n");
            sb.append("      - traefik.http.routers.").append(router).append("-secure.tls.certresolver=le\n");
        }
        if (strip) {
            String mw = router + "-strip";
            sb.append("      - traefik.http.middlewares.").append(mw)
                    .append(".stripprefix.prefixes=").append(pathPrefix).append("\n");
            sb.append("      - traefik.http.routers.").append(router).append(".middlewares=").append(mw).append("\n");
            if (tls) {
                sb.append("      - traefik.http.routers.").append(router)
                        .append("-secure.middlewares=").append(mw).append("\n");
            }
        }
    }

    /** Service qui porte les routeurs des adresses secondaires : la racine « / », sinon le premier. */
    static PlatformServiceSpec aliasCarrier(PlatformSpec platform) {
        if (platform.aliases().isEmpty() || platform.services().isEmpty()) {
            return null;
        }
        return platform.services().stream()
                .filter(s -> s.pathPrefix() == null || s.pathPrefix().isBlank() || "/".equals(s.pathPrefix()))
                .findFirst()
                .orElse(platform.services().get(0));
    }

    /** {@code $$} : compose interpréterait {@code $1} comme une variable. */
    private static void appendAliasRedirect(StringBuilder sb, PlatformSpec platform, String mainHost,
                                            ServerProfile profile) {
        String router = platform.id() + "-alias";
        String mw = router + "-redirect";
        String rule = platform.aliases().stream()
                .map(a -> "Host(`" + a + "`)")
                .reduce((a, b) -> a + " || " + b)
                .orElseThrow();
        String target = profile.scheme() + "://" + profile.authority(mainHost) + "/$${1}";
        sb.append("      - traefik.http.middlewares.").append(mw)
                .append(".redirectregex.regex=^https?://[^/]+/(.*)\n");
        sb.append("      - traefik.http.middlewares.").append(mw)
                .append(".redirectregex.replacement=").append(target).append("\n");
        sb.append("      - traefik.http.middlewares.").append(mw).append(".redirectregex.permanent=true\n");
        sb.append("      - \"traefik.http.routers.").append(router).append(".rule=").append(rule).append("\"\n");
        sb.append("      - traefik.http.routers.").append(router).append(".entrypoints=web\n");
        sb.append("      - traefik.http.routers.").append(router).append(".middlewares=").append(mw).append("\n");
        if (!profile.httpOnly()) {
            sb.append("      - \"traefik.http.routers.").append(router).append("-secure.rule=").append(rule).append("\"\n");
            sb.append("      - traefik.http.routers.").append(router).append("-secure.entrypoints=websecure\n");
            sb.append("      - traefik.http.routers.").append(router).append("-secure.tls=true\n");
            sb.append("      - traefik.http.routers.").append(router).append("-secure.tls.certresolver=le\n");
            sb.append("      - traefik.http.routers.").append(router).append("-secure.middlewares=").append(mw).append("\n");
        }
    }

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * Une IP n'est pas un nom de site : derrière une NAT, le même serveur est joint par l'adresse publique
     * et l'adresse interne. On ne filtre alors que sur le chemin.
     */
    static String routerRule(String hostname, String pathPrefix) {
        boolean ip = hostname != null && IPV4.matcher(hostname).matches();
        String path = pathPrefix == null ? null : "PathPrefix(`" + pathPrefix + "`)";
        if (ip) {
            return path == null ? "PathPrefix(`/`)" : path;
        }
        String host = "Host(`" + hostname + "`)";
        return path == null ? host : host + " && " + path;
    }

    private static String yamlQuote(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
