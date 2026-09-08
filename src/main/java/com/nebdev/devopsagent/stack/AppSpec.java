package com.nebdev.devopsagent.stack;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Déclaration d’une app cargo (ADR-029). Pas de logique de provision dans le dépôt Git.
 */
public record AppSpec(String id, String gitUrl, String gitRef, String hostname, int port) {

    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern GIT = Pattern.compile("^https://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9._/-]{1,80}$");
    private static final Pattern HOST = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");

    public static AppSpec normalized(String id, String gitUrl, String gitRef, String hostname, Integer port) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Indique un identifiant d’app (lettres minuscules, chiffres, tirets).");
        }
        String slug = id.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("Identifiant invalide. Exemple : mon-app");
        }
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException("Indique l’URL Git HTTPS publique.");
        }
        String git = gitUrl.trim();
        if (!GIT.matcher(git).matches() || git.contains("..")) {
            throw new IllegalArgumentException("URL Git : uniquement HTTPS publique (pas SSH, pas de shell).");
        }
        String ref = (gitRef == null || gitRef.isBlank()) ? "main" : gitRef.trim();
        if (!REF.matcher(ref).matches()) {
            throw new IllegalArgumentException("Branche / tag Git invalide.");
        }
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("Indique le nom d’hôte public (DNS vers ce VPS).");
        }
        String host = hostname.trim().toLowerCase(Locale.ROOT);
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Nom d’hôte invalide.");
        }
        int p = port == null ? 8080 : port;
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException("Port interne entre 1 et 65535.");
        }
        return new AppSpec(slug, git, ref, host, p);
    }
}
