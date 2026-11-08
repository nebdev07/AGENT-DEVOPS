package com.nebdev.devopsagent.journal;

import com.nebdev.devopsagent.stack.PlatformServiceSpec;
import com.nebdev.devopsagent.stack.PlatformSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détail lisible d’un déploiement de plateforme pour le journal / rapports
 * (ex. « 3 applications : frontoffice, back-office, backend » + commits clonés).
 */
public final class DeployActivity {

    private static final Pattern CLONED = Pattern.compile("(?m)^CLONED=([^=\\s@]+)@([0-9a-fA-F]{4,40})\\s*$");

    private DeployActivity() {}

    /** Libellé métier d’un service (api → backend, www → frontoffice, …). */
    public static String roleLabel(PlatformServiceSpec s) {
        String id = s == null || s.id() == null ? "" : s.id().strip().toLowerCase(Locale.ROOT);
        String template = s == null || s.template() == null ? "" : s.template().strip().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "api", "backend" -> "backend (API)";
            case "www", "site", "client", "front", "frontoffice" -> "frontoffice (site)";
            case "backoffice", "admin", "bo" -> "back-office";
            default -> {
                if (template.startsWith("spring")) {
                    yield id + " (API / backend)";
                }
                if (template.equals("next") || template.equals("node") || template.equals("static")) {
                    yield id + " (front)";
                }
                yield id.isBlank() ? "service" : id;
            }
        };
    }

    /** Commits réellement clonés dans le journal du rôle ({@code CLONED=service@sha}). */
    public static Map<String, String> parseClones(String rawLog) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawLog == null || rawLog.isBlank()) {
            return out;
        }
        Matcher m = CLONED.matcher(rawLog);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    public static Map<String, String> details(PlatformSpec platform, String rawLog, boolean ok) {
        Map<String, String> details = new LinkedHashMap<>();
        if (platform == null) {
            details.put("summary", ok ? "Plateforme déployée" : "Échec du déploiement");
            return details;
        }
        details.put("id", platform.id());
        details.put("routing", platform.routing() == null ? "" : platform.routing());
        details.put("count", String.valueOf(platform.services().size()));

        Map<String, String> clones = parseClones(rawLog);
        List<String> apps = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (PlatformServiceSpec s : platform.services()) {
            String role = roleLabel(s);
            String ref = clones.getOrDefault(s.id(),
                    s.gitRef() == null || s.gitRef().isBlank() ? "main" : s.gitRef().strip());
            String shortRef = ref.length() > 12 ? ref.substring(0, 7) : ref;
            String repo = shortRepo(s.gitUrl());
            apps.add(role + " [" + s.id() + "@" + shortRef + "]");
            lines.add(role + " — id=" + s.id()
                    + (repo.isBlank() ? "" : ", dépôt " + repo)
                    + ", ref " + shortRef
                    + (s.pathPrefix() == null || s.pathPrefix().isBlank() ? "" : ", chemin " + s.pathPrefix())
                    + (s.template() == null || s.template().isBlank() ? "" : ", modèle " + s.template()));
            String host = s.hostname() == null ? "" : s.hostname().strip();
            String path = s.pathPrefix() == null || s.pathPrefix().isBlank() ? "/" : s.pathPrefix();
            if (!host.isBlank()) {
                urls.add(role + " → https://" + host + ("/".equals(path) ? "/" : path));
            }
        }
        details.put("apps", String.join(" · ", apps));
        details.put("services", String.join(" | ", lines));
        if (!urls.isEmpty()) {
            details.put("urls", String.join(" · ", urls));
        }
        if (!clones.isEmpty()) {
            details.put("commits", clones.entrySet().stream()
                    .map(e -> e.getKey() + "@" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }
        int n = platform.services().size();
        details.put("summary", ok
                ? "Déployé " + n + " application" + (n > 1 ? "s" : "") + " : " + String.join(", ", apps)
                : "Échec du déploiement de « " + platform.id() + " » (" + n + " application(s) prévues)");
        return details;
    }

    static String shortRepo(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            return "";
        }
        String u = gitUrl.strip();
        int slash = Math.max(u.lastIndexOf('/'), u.lastIndexOf(':'));
        String name = slash >= 0 ? u.substring(slash + 1) : u;
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }
}
