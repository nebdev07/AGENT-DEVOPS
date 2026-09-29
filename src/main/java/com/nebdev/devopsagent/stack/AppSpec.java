package com.nebdev.devopsagent.stack;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Déclaration d’une app cargo (ADR-029). Pas de logique de provision dans le dépôt Git.
 */
public record AppSpec(String id, String gitUrl, String gitRef, String hostname, int port) {

    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9._/-]{1,80}$");
    private static final Pattern HOST = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");

    private static final String WHERE_ID = "Champ Identifiant — bande Applications (pas « Analyser le VPS »)";
    private static final String WHERE_REF = "Champ Branche — bande Applications";
    private static final String WHERE_HOST = "Champ 2 · Nom d’hôte — bande Applications (ce n’est pas l’URL Git)";
    private static final String WHERE_PORT = "Champ Port interne — bande Applications";

    public static AppSpec normalized(String id, String gitUrl, String gitRef, String hostname, Integer port) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "aucun identifiant saisi.",
                    WHERE_ID,
                    "saisis un nom court avec tirets, ex. my-bourse (les underscores _ deviennent des tirets)."));
        }
        // Underscores → tirets (souvent collés depuis un nom de repo).
        String original = id.trim();
        String slug = original.toLowerCase(Locale.ROOT).replace('_', '-');
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    slugCause(original, slug),
                    WHERE_ID,
                    "utilise seulement a-z, 0-9 et tirets ; commence par une lettre ; max 32 car. Exemple : my-bourse."));
        }
        String git = GitUrls.normalize(gitUrl);
        String ref = (gitRef == null || gitRef.isBlank()) ? "main" : gitRef.trim();
        if (!REF.matcher(ref).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "branche / tag « " + abbreviate(ref) + " » contient des caractères interdits.",
                    WHERE_REF,
                    "ex. main ou master (lettres, chiffres, . _ / -)."));
        }
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "nom d’hôte public manquant.",
                    WHERE_HOST,
                    "laisse le champ vide pour une proposition sslip, ou saisis un DNS du type my-bourse.<IP>.sslip.io — pas une URL GitHub."));
        }
        String host = hostname.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("http://") || host.startsWith("https://") || host.contains("github.com") || host.contains("/")) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "tu as collé une URL (souvent GitHub) dans le nom d’hôte : « " + abbreviate(hostname.trim()) + " ».",
                    WHERE_HOST,
                    "mets https://github.com/… dans le champ Git. Ici : seulement un DNS, ex. my-bourse.57.128.186.38.sslip.io"));
        }
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "nom d’hôte « " + abbreviate(host) + " » invalide (caractères ou format DNS).",
                    WHERE_HOST,
                    "pas d’URL, pas d’espace. Exemple : my-bourse.57.128.186.38.sslip.io"));
        }
        int p = port == null ? 8080 : port;
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "port " + p + " hors plage.",
                    WHERE_PORT,
                    "indique le port écouté dans le conteneur (souvent 8080), entre 1 et 65535."));
        }
        return new AppSpec(slug, git, ref, host, p);
    }

    private static String slugCause(String original, String slug) {
        if (slug.isEmpty()) {
            return "après normalisation, l’identifiant est vide.";
        }
        if (original.indexOf('.') >= 0) {
            return "« " + abbreviate(original) + " » ressemble à un nom d’hôte DNS (il contient des points)."
                    + " L’identifiant est un slug court sans points (ex. my-bourse)."
                    + " Le DNS complet va dans « Nom d’hôte » (ex. my-bourse.apps.nebdev.org).";
        }
        if (!Character.isLetter(slug.charAt(0))) {
            return "« " + abbreviate(original) + " » (normalisé « " + slug
                    + " ») ne commence pas par une lettre.";
        }
        if (slug.length() > 32) {
            return "trop long (" + slug.length() + " car., max 32) : « " + abbreviate(original) + " ».";
        }
        if (original.indexOf('_') >= 0) {
            return "après conversion _→-, « " + slug + " » reste invalide (autre caractère interdit).";
        }
        return "« " + abbreviate(original) + " » (normalisé « " + slug
                + " ») : seuls a-z, chiffres et tirets sont acceptés (pas d’espace, pas de point).";
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String t = value.trim();
        return t.length() <= 64 ? t : t.substring(0, 61) + "…";
    }
}
