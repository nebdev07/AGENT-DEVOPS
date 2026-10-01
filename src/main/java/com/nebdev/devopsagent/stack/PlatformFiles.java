package com.nebdev.devopsagent.stack;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fichiers que l’agent pose sur le VPS pour une plateforme : Dockerfile de modèle (V7-06) et env d’exécution.
 */
final class PlatformFiles {

    static final String ENTRYPOINT = "autopilot-entrypoint.sh";
    static final String ENV_MARKER = "@env";

    private PlatformFiles() {
    }

    static String dockerfile(PlatformServiceSpec s) {
        String body = switch (s.template()) {
            case "next" -> resource("next.Dockerfile");
            case "node" -> resource("node.Dockerfile");
            case "spring-boot-11" -> resource("spring-boot.Dockerfile").replace("#JDK#", "11");
            case "spring-boot-17" -> resource("spring-boot.Dockerfile").replace("#JDK#", "17");
            case "spring-boot-21" -> resource("spring-boot.Dockerfile").replace("#JDK#", "21");
            default -> throw new IllegalArgumentException("Modèle Dockerfile inconnu : " + s.template());
        };
        return body.replace("#PORT#", Integer.toString(s.port()))
                .replace("#BUILD_ARGS#", buildArgLines(s));
    }

    /** Fichiers à écrire dans le dossier de build (context) du service. */
    static Map<String, String> contextFiles(PlatformServiceSpec s) {
        if (!s.templated()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put(PlatformCompose.TEMPLATE_DOCKERFILE, dockerfile(s));
        if (s.template().startsWith("spring-boot-")) {
            out.put(ENTRYPOINT, resource("spring-entrypoint.sh"));
        }
        return out;
    }

    /** Contenu de svc-&lt;id&gt;.env hors Redis/DB (ajoutés par le rôle, secrets serveur). */
    static String envFile(PlatformServiceSpec s) {
        StringBuilder sb = new StringBuilder();
        sb.append("NODE_ENV=production\n");
        for (Map.Entry<String, String> e : new TreeMap<>(s.env()).entrySet()) {
            sb.append(e.getKey()).append('=').append(quote(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Lignes {@code sid \t chemin \t base64} : chemin relatif au dépôt cloné, ou {@value #ENV_MARKER}.
     */
    static String bundle(PlatformSpec platform) {
        StringBuilder sb = new StringBuilder();
        for (PlatformServiceSpec s : platform.services()) {
            line(sb, s.id(), ENV_MARKER, envFile(s));
            String prefix = s.context().isBlank() ? "" : s.context() + "/";
            contextFiles(s).forEach((name, content) -> line(sb, s.id(), prefix + name, content));
        }
        return sb.toString();
    }

    /** Compose v2 interpole $ dans les env_file : quotes simples = littéral. */
    static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "$$") + "\"";
    }

    private static String buildArgLines(PlatformServiceSpec s) {
        if (s.buildArgs().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : new TreeMap<>(s.buildArgs()).keySet()) {
            sb.append("ARG ").append(key).append('\n');
            sb.append("ENV ").append(key).append("=${").append(key).append("}\n");
        }
        return sb.toString().stripTrailing();
    }

    private static void line(StringBuilder sb, String sid, String path, String content) {
        sb.append(sid).append('\t').append(path).append('\t')
                .append(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)))
                .append('\n');
    }

    private static String resource(String name) {
        String path = "templates/platform/" + name;
        try {
            byte[] bytes = new ClassPathResource(path).getInputStream().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger " + path, e);
        }
    }
}
