package com.nebdev.devopsagent.stack;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RoleScript {

    private static final Set<String> ALLOWED = Set.of("security", "docker", "traefik", "mariadb", "deploy", "app");
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
            return body;
        }
        Set<String> on = Set.copyOf(selected);
        StringBuilder prefix = new StringBuilder();
        for (Map.Entry<String, String> e : FLAGS.entrySet()) {
            prefix.append(e.getValue()).append('=').append(on.contains(e.getKey()) ? "1" : "0").append('\n');
        }
        return prefix + body;
    }

    public static String loadApp(AppSpec app) {
        if (app == null) {
            throw new IllegalArgumentException("App manquante.");
        }
        return "APP_SLUG=" + app.id() + "\n"
                + "APP_GIT=" + app.gitUrl() + "\n"
                + "APP_REF=" + app.gitRef() + "\n"
                + "APP_HOST=" + app.hostname() + "\n"
                + "APP_PORT=" + app.port() + "\n"
                + load("app");
    }
}
