package com.nebdev.devopsagent.stack;

/**
 * Compose déterministe pour une plateforme (ADR-036). Aucun port publié.
 */
final class PlatformCompose {

    private PlatformCompose() {
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

    static String routerName(PlatformSpec platform, PlatformServiceSpec s) {
        return platform.id() + "-" + s.id();
    }

    static String yaml(PlatformSpec platform) {
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
            if (needsUploads(s)) {
                sb.append("    volumes:\n");
                sb.append("      - plat_").append(platform.id().replace('-', '_'))
                        .append("_uploads:/app/data\n");
            }
            sb.append("    networks:\n");
            sb.append("      - proxy\n");
            if (needsData(s) && data) {
                sb.append("      - data\n");
            }
            sb.append("    labels:\n");
            sb.append("      - traefik.enable=true\n");
            sb.append("      - traefik.docker.network=proxy\n");
            sb.append("      - vps.autopilot.platform=").append(platform.id()).append("\n");
            sb.append("      - vps.autopilot.service=").append(s.id()).append("\n");
            appendRouterLabels(sb, router, s.hostname(), s.pathPrefix(), platform.pathRouting());
            sb.append("      - traefik.http.services.").append(router)
                    .append(".loadbalancer.server.port=").append(s.port()).append("\n");
        }
        if (uploads) {
            sb.append("volumes:\n");
            sb.append("  plat_").append(platform.id().replace('-', '_')).append("_uploads:\n");
        }
        sb.append("networks:\n");
        sb.append("  proxy:\n");
        sb.append("    external: true\n");
        if (data) {
            sb.append("  data:\n");
            sb.append("    external: true\n");
        }
        return sb.toString();
    }

    static String publicUrl(PlatformSpec platform, PlatformServiceSpec s) {
        if (platform.pathRouting() && s.pathPrefix() != null && !s.pathPrefix().isBlank()
                && !"/".equals(s.pathPrefix())) {
            return "https://" + s.hostname() + s.pathPrefix() + "/";
        }
        return "https://" + s.hostname() + "/";
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
                    .append(s.context());
        }
        return sb.toString();
    }

    private static void appendRouterLabels(
            StringBuilder sb,
            String router,
            String hostname,
            String pathPrefix,
            boolean pathRouting
    ) {
        String rule = "Host(`" + hostname + "`)";
        boolean strip = pathRouting && pathPrefix != null && !pathPrefix.isBlank() && !"/".equals(pathPrefix);
        if (strip) {
            rule = rule + " && PathPrefix(`" + pathPrefix + "`)";
        }
        sb.append("      - \"traefik.http.routers.").append(router).append(".rule=").append(rule).append("\"\n");
        sb.append("      - traefik.http.routers.").append(router).append(".entrypoints=web\n");
        sb.append("      - \"traefik.http.routers.").append(router).append("-secure.rule=").append(rule).append("\"\n");
        sb.append("      - traefik.http.routers.").append(router).append("-secure.entrypoints=websecure\n");
        sb.append("      - traefik.http.routers.").append(router).append("-secure.tls=true\n");
        sb.append("      - traefik.http.routers.").append(router).append("-secure.tls.certresolver=le\n");
        if (strip) {
            String mw = router + "-strip";
            sb.append("      - traefik.http.middlewares.").append(mw)
                    .append(".stripprefix.prefixes=").append(pathPrefix).append("\n");
            sb.append("      - traefik.http.routers.").append(router).append(".middlewares=").append(mw).append("\n");
            sb.append("      - traefik.http.routers.").append(router)
                    .append("-secure.middlewares=").append(mw).append("\n");
        }
    }

    private static String yamlQuote(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
