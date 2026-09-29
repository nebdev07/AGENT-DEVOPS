package com.nebdev.devopsagent.stack;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Git HTTPS public ou SSH lecture seule (GitHub / GitLab). Pas de mot de passe dans l’URL.
 */
public final class GitUrls {

    private static final Pattern HTTPS = Pattern.compile(
            "^https://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+$");
    private static final Pattern SSH = Pattern.compile(
            "^(?:git@|ssh://git@)(github\\.com|gitlab\\.com)[:/][A-Za-z0-9._/-]+(?:\\.git)?$",
            Pattern.CASE_INSENSITIVE);

    private GitUrls() {
    }

    public static String normalize(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "URL Git absente.",
                    "Champ Git",
                    "HTTPS public (https://github.com/org/repo.git) ou SSH privé (git@github.com:org/repo.git)."));
        }
        String git = gitUrl.trim();
        if (git.contains("..")) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "URL Git « " + abbreviate(git) + " » invalide.",
                    "Champ Git",
                    "pas de .. dans le chemin."));
        }
        if (git.toLowerCase(Locale.ROOT).matches("^https://[^/]*:[^/@]+@.*")) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "l’URL contient un mot de passe ou un jeton.",
                    "Champ Git",
                    "pas de https://user:token@…. Pour un dépôt privé : git@github.com:org/repo.git + clé lecture seule."));
        }
        if (HTTPS.matcher(git).matches() && git.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return git;
        }
        if (SSH.matcher(git).matches()) {
            if (!git.toLowerCase(Locale.ROOT).endsWith(".git")) {
                git = git + ".git";
            }
            return git;
        }
        boolean sshShape = git.startsWith("git@") || git.toLowerCase(Locale.ROOT).startsWith("ssh://");
        throw new IllegalArgumentException(OperatorMessage.of(
                "URL « " + abbreviate(git) + " » refusée"
                        + (sshShape ? " (hôte SSH hors GitHub/GitLab, ou format incorrect)." : "."),
                "Champ Git",
                sshShape
                        ? "SSH seulement vers github.com ou gitlab.com, ex. git@github.com:org/repo.git. "
                        + "Ajoute la clé publique en Deploy key lecture seule."
                        : "HTTPS public, ou git@github.com:org/repo.git pour un dépôt privé."));
    }

    public static boolean ssh(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            return false;
        }
        String g = gitUrl.trim().toLowerCase(Locale.ROOT);
        return g.startsWith("git@") || g.startsWith("ssh://git@");
    }

    private static String abbreviate(String value) {
        String t = value.trim();
        return t.length() <= 64 ? t : t.substring(0, 61) + "…";
    }
}
