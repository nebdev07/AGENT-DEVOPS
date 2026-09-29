package com.nebdev.devopsagent.stack;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RoleScript {

    private static final Set<String> ALLOWED = Set.of(
            "security", "docker", "traefik", "mariadb", "deploy", "redis", "backups", "app", "platform");
    private static final Map<String, String> FLAGS = new LinkedHashMap<>();

    static {
        FLAGS.put("ufw", "APPLY_UFW");
        FLAGS.put("fail2ban", "APPLY_FAIL2BAN");
        FLAGS.put("ssh-harden", "APPLY_SSH");
        FLAGS.put("unattended", "APPLY_UPDATES");
        FLAGS.put("engine", "APPLY_DOCKER");
        FLAGS.put("compose", "APPLY_COMPOSE");
        FLAGS.put("docker-group", "APPLY_DOCKER_GROUP");
        FLAGS.put("proxy", "APPLY_PROXY");
        FLAGS.put("https", "APPLY_HTTPS");
        FLAGS.put("instance", "APPLY_INSTANCE");
        FLAGS.put("per-project", "APPLY_PER_PROJECT");
        FLAGS.put("user", "APPLY_USER");
        FLAGS.put("keys", "APPLY_KEYS");
        FLAGS.put("ssh-allow", "APPLY_SSH_ALLOW");
        FLAGS.put("internal", "APPLY_INTERNAL");
        FLAGS.put("encrypted-dump", "APPLY_ENCRYPTED_DUMP");
        FLAGS.put("offsite", "APPLY_OFFSITE");
        FLAGS.put("restore-proof", "APPLY_RESTORE_PROOF");
    }

    private RoleScript() {}

    public static String load(String id) {
        return load(id, List.of());
    }

    public static String load(String id, List<String> selected) {
        if (!ALLOWED.contains(id)) {
            throw new IllegalArgumentException("Rôle non exécutable: " + id);
        }
        String resource = "roles/" + id + "/apply.sh";
        String body;
        try {
            byte[] bytes = new ClassPathResource(resource).getInputStream().readAllBytes();
            body = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger " + resource, e);
        }
        if (selected == null || selected.isEmpty()) {
            if ("traefik".equals(id)) {
                // HTTPS toujours actif à l’install Traefik (ADR-033).
                return "APPLY_PROXY=1\nAPPLY_HTTPS=1\n" + body;
            }
            return body;
        }
        Set<String> on = new java.util.LinkedHashSet<>(selected);
        if ("traefik".equals(id)) {
            on.add("proxy");
            on.add("https");
        }
        StringBuilder prefix = new StringBuilder();
        for (Map.Entry<String, String> e : FLAGS.entrySet()) {
            prefix.append(e.getValue()).append('=').append(on.contains(e.getKey()) ? "1" : "0").append('\n');
        }
        return prefix + body;
    }

    public static String loadApp(AppSpec app) {
        return loadApp(app, null);
    }

    public static String loadApp(AppSpec app, String scaffoldDockerfileBase64) {
        if (app == null) {
            throw new IllegalArgumentException("App manquante.");
        }
        StringBuilder prefix = new StringBuilder();
        prefix.append("APP_SLUG=").append(app.id()).append('\n');
        prefix.append("APP_GIT=").append(app.gitUrl()).append('\n');
        prefix.append("APP_REF=").append(app.gitRef()).append('\n');
        prefix.append("APP_HOST=").append(app.hostname()).append('\n');
        prefix.append("APP_PORT=").append(app.port()).append('\n');
        if (scaffoldDockerfileBase64 != null && !scaffoldDockerfileBase64.isBlank()) {
            // Une seule ligne base64 — pas d’espaces.
            String b64 = scaffoldDockerfileBase64.replaceAll("\\s", "");
            prefix.append("APP_SCAFFOLD_B64=").append(b64).append('\n');
        }
        return prefix + load("app");
    }

    public static String loadPlatform(PlatformSpec platform) {
        if (platform == null) {
            throw new IllegalArgumentException("Plateforme manquante.");
        }
        String compose = PlatformCompose.yaml(platform);
        String manifest = PlatformCompose.manifest(platform);
        String b64c = java.util.Base64.getEncoder().encodeToString(compose.getBytes(StandardCharsets.UTF_8));
        String b64m = java.util.Base64.getEncoder().encodeToString(manifest.getBytes(StandardCharsets.UTF_8));
        StringBuilder prefix = new StringBuilder();
        prefix.append("PLATFORM_ID=").append(platform.id()).append('\n');
        prefix.append("PLATFORM_NEED_DB=").append(PlatformCompose.needsDb(platform) ? "1" : "0").append('\n');
        prefix.append("PLATFORM_NEED_REDIS=").append(PlatformCompose.needsRedis(platform) ? "1" : "0").append('\n');
        prefix.append("PLATFORM_COMPOSE_B64=").append(b64c).append('\n');
        prefix.append("PLATFORM_MANIFEST_B64=").append(b64m).append('\n');
        return prefix + load("platform");
    }
}
