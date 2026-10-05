package com.nebdev.devopsagent.stack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprète la sortie distante (ou un IOException SSH) en message Cause / Où / À faire.
 */
final class AppRemoteFailure {

    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^.*?Error:.*$");
    private static final Pattern FAILED_COMPILE = Pattern.compile("Failed to compile[\\s\\S]{0,400}");

    private AppRemoteFailure() {
    }

    static void throwIfKnown(String raw, AppSpec app) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (raw.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "sudo sans mot de passe refusé pour l’utilisateur SSH.",
                    "Connexion SSH pendant Déployer",
                    "utilise un user avec NOPASSWD sudo (Aide › 3. Clé SSH › méthode D, dernière ligne), ou corrige sudoers."));
        }
        if (raw.contains("NEED_DEPLOY_USER")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’utilisateur deploy n’existe pas sur le VPS au moment du script.",
                    "Étape Déployer — Compte deploy",
                    "Installer « Compte deploy », Analyser, puis Déployer à nouveau."));
        }
        if (raw.contains("NEED_TRAEFIK") || raw.contains("DOCKER_MISSING")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "Docker ou le réseau Traefik (proxy) manquent sur le VPS.",
                    "Étape Déployer — runtime",
                    "vérifie Docker + Traefik en SKIP dans Installations, réanalyse, puis Déployer."));
        }
        if (raw.contains("NEED_DOCKERFILE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "le dépôt Git n’a pas de Dockerfile à la racine (un docker-compose.yml seul ne suffit pas).",
                    "Étape Déployer — dépôt " + app.gitUrl(),
                    "ajoute un Dockerfile à la racine de la branche « " + app.gitRef()
                            + " », pousse sur GitHub, puis Déployer — ou confirme le rattrapage template."));
        }
        if (raw.contains("NEED_GIT_DEPLOY_KEY")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "clone SSH sans clé Git lecture seule.",
                    "Étape Déployer — clé Git",
                    "Génère la clé dans la console, ajoute la publique en Deploy key read-only, puis Déployer."));
        }
        if (raw.contains("GIT_CLONE_FAILED")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "clone impossible de « " + app.gitUrl() + " » (branche « " + app.gitRef() + " »).",
                    "Étape Déployer — Git",
                    "HTTPS public, ou git@github.com:org/repo.git + Deploy key lecture seule. "
                            + detailFromRemote(raw)));
        }
        if (raw.contains("NEED_APP_SPEC")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "spécification d’app incomplète côté agent.",
                    "Étape Déployer",
                    "ré-enregistre l’app (Enregistrer dans la liste) puis Déployer."));
        }
        if (raw.contains("UNSUPPORTED_OS")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "OS distant hors Debian/Ubuntu.",
                    "Étape Déployer — cible",
                    "utilise un VPS Debian ou Ubuntu pour l’instant."));
        }
        if (raw.contains("COMPOSE_MISSING")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "docker compose absent sur le VPS.",
                    "Étape Déployer — Docker Compose",
                    "installe Docker (détail Compose) depuis Installations, puis Déployer."));
        }
        if (raw.contains("BUILD_FAILED") || raw.contains("Failed to compile") || raw.contains("failed to solve")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "le build Docker a échoué" + buildCauseSuffix(raw),
                    "Étape Déployer — build image « " + app.id() + " »",
                    "corrige le code du dépôt (souvent ESLint/TypeScript au `next build`), pousse, puis Déployer. "
                            + "Le template Next ignore maintenant eslint pendant le build cargo."));
        }
    }

    static IllegalStateException unknown(IOException e, AppSpec app) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        throwIfKnown(msg, app);
        if (msg.contains("Cloning into") || msg.contains("git clone") || msg.contains("fatal:")) {
            return new IllegalStateException(OperatorMessage.of(
                    "échec pendant le clone ou juste après (souvent : pas de Dockerfile à la racine, ou clone Git).",
                    "Étape Déployer — " + app.gitUrl(),
                    "1) Vérifie un Dockerfile à la racine sur GitHub. 2) Dépôt HTTPS public. "
                            + "Détail distant : " + abbreviate(msg, 280)), e);
        }
        if (msg.contains("SCAFFOLD_DOCKERFILE_APPLIED") || msg.contains("docker driver") || msg.contains("npm run build")) {
            return new IllegalStateException(OperatorMessage.of(
                    "le build Docker a échoué après rattrapage Dockerfile" + buildCauseSuffix(msg),
                    "Étape Déployer — build « " + app.id() + " »",
                    "souvent un lint/typecheck du projet (ex. prefer-const). Corrige le dépôt ou relance : "
                            + "le template Next ignore eslint/tsc bloquants pendant le cargo."), e);
        }
        return new IllegalStateException(OperatorMessage.of(
                "commande distante en échec (exit ≠ 0).",
                "Étape Déployer — " + app.id(),
                "Détail : " + abbreviate(msg, 320)), e);
    }

    private static String buildCauseSuffix(String raw) {
        List<String> hits = new ArrayList<>();
        Matcher err = ERROR_LINE.matcher(raw);
        while (err.find() && hits.size() < 3) {
            String line = err.group().replaceAll("\\s+", " ").strip();
            if (line.length() > 160) {
                line = line.substring(0, 157) + "…";
            }
            hits.add(line);
        }
        if (!hits.isEmpty()) {
            return " — " + String.join(" | ", hits);
        }
        Matcher fc = FAILED_COMPILE.matcher(raw);
        if (fc.find()) {
            return " — " + abbreviate(fc.group(), 200);
        }
        return ".";
    }

    private static String detailFromRemote(String raw) {
        int i = raw.indexOf("###STDERR###");
        if (i < 0) {
            return "";
        }
        String err = raw.substring(i + "###STDERR###".length()).strip();
        if (err.isBlank() || err.startsWith("Cloning into")) {
            return "";
        }
        return "Stderr : " + abbreviate(err, 220);
    }

    private static String abbreviate(String value, int max) {
        String t = value.replace('\n', ' ').strip();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 3)) + "…";
    }
}
