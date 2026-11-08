package com.nebdev.devopsagent.stack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprète la sortie distante (ou un IOException SSH) en message Cause / Où / À faire.
 */
final class AppRemoteFailure {

    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^.*?Error:.*$");
    private static final Pattern FAILED_COMPILE = Pattern.compile("Failed to compile[\\s\\S]{0,400}");
    private static final Pattern COMPOSE_TARGET = Pattern.compile(
            "(?:> \\[|#\\d+ \\[)([a-z][a-z0-9-]{0,31}) (?:build|stage|deps|builder|runner)\\b");
    private static final Pattern BUILD_FAILED_SERVICE = Pattern.compile(
            "(?m)^BUILD_FAILED_SERVICE=([a-z][a-z0-9-]{0,31})\\s*$");
    private static final Pattern ONLY_SERVICE = Pattern.compile(
            "(?m)^ONLY_SERVICE=([a-z][a-z0-9-]{0,31})\\s*$");
    private static final Pattern MAVEN_SYMBOL = Pattern.compile(
            "(?m)\\[ERROR\\]\\s+(/[^\\s]+\\.java):\\[(\\d+),(\\d+)\\]\\s+cannot find symbol");
    private static final Pattern MAVEN_SYMBOL_NAME = Pattern.compile(
            "(?m)\\s+symbol:\\s+method\\s+(\\S+)");
    /** Ex. « location: variable dto of type org.nwanyo.arc.utils.dto.MediasDto ». */
    private static final Pattern MAVEN_LOCATION_TYPE = Pattern.compile(
            "(?m)\\s+location:\\s+(?:variable\\s+\\S+\\s+of\\s+type\\s+|class\\s+)([\\w.$]+)");
    private static final Pattern SERVICE_LINE = Pattern.compile("(?m)^SERVICE=([a-z][a-z0-9-]{0,31})\\s*$");
    private static final Pattern GIT_URL_LINE = Pattern.compile("(?m)^GIT_URL=(.+)\\s*$");
    private static final Pattern GIT_REF_LINE = Pattern.compile("(?m)^GIT_REF=([\\w./_-]+)\\s*$");

    private AppRemoteFailure() {
    }

    static void throwIfKnown(String raw, AppSpec app) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        throwCommonInfra(raw, app == null ? null : app.gitUrl(), app == null ? null : app.gitRef());
        if (raw.contains("BUILD_FAILED") || raw.contains("Failed to compile") || raw.contains("failed to solve")) {
            String id = app == null ? "app" : app.id();
            throw new IllegalStateException(OperatorMessage.of(
                    "le build Docker a échoué" + buildCauseSuffix(raw),
                    "Étape Déployer — build image « " + id + " »",
                    nextActionForApp(raw, id)));
        }
    }

    /**
     * Échec plateforme : nomme le service fautif et dit s’il faut « Déployer X » ou « Tout déployer ».
     */
    static void throwIfKnownPlatform(String raw, PlatformSpec platform, String onlyServiceId) {
        if (raw == null || raw.isBlank() || platform == null) {
            return;
        }
        Set<String> known = new LinkedHashSet<>();
        for (PlatformServiceSpec s : platform.services()) {
            known.add(s.id());
        }
        String resolvedSid = resolveFailedService(raw, known, onlyServiceId);
        if (resolvedSid == null) {
            Matcher sm = SERVICE_LINE.matcher(raw);
            if (sm.find()) {
                resolvedSid = sm.group(1);
            }
        }
        final String failedSid = resolvedSid;
        PlatformServiceSpec svc = failedSid == null ? null : platform.services().stream()
                .filter(s -> s.id().equals(failedSid))
                .findFirst()
                .orElse(null);
        String gitUrl = firstNonBlank(matchGroup(GIT_URL_LINE, raw), svc == null ? null : svc.gitUrl());
        String gitRef = firstNonBlank(matchGroup(GIT_REF_LINE, raw), svc == null ? null : svc.gitRef(), "main");

        if (raw.contains("GIT_CLONE_FAILED")) {
            throwGitCloneFailed(raw, platform.id(), failedSid, gitUrl, gitRef);
        }
        if (raw.contains("NEED_GIT_DEPLOY_KEY")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "clone SSH sans clé Git lecture seule"
                            + (failedSid == null ? "." : " pour le service « " + failedSid + " »."),
                    "Déployer plateforme « " + platform.id() + " » — clé Git"
                            + (failedSid == null ? "" : " / " + failedSid),
                    "Dans Clés Git : génère la clé du dépôt « " + (gitUrl == null ? "?" : shortRepo(gitUrl))
                            + " », ajoute la publique en Deploy key lecture seule sur GitHub, puis "
                            + (failedSid == null ? "Déployer." : "« Déployer " + failedSid + " ».")));
        }
        throwCommonInfra(raw, gitUrl, gitRef);
        if (!(raw.contains("BUILD_FAILED") || raw.contains("Failed to compile") || raw.contains("failed to solve"))) {
            return;
        }
        String kind = classifyBuild(raw);
        String cause = "le build Docker a échoué sur le service « "
                + (failedSid == null ? "?" : failedSid) + " » (" + kind + ")"
                + buildCauseSuffix(raw);
        String where = "Déployer plateforme « " + platform.id() + " » — service « "
                + (failedSid == null ? "inconnu" : failedSid) + " »";
        throw new IllegalStateException(OperatorMessage.of(cause, where,
                nextActionForPlatform(platform, svc, failedSid, kind)));
    }

    private static void throwGitCloneFailed(String raw, String platformId, String serviceId,
                                            String gitUrl, String gitRef) {
        String repo = gitUrl == null || gitUrl.isBlank() ? "dépôt" : gitUrl;
        String where = "Déployer plateforme « " + platformId + " » — Git"
                + (serviceId == null ? "" : " / " + serviceId);
        if (raw.toLowerCase(Locale.ROOT).contains("permission denied")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "clone impossible de « " + repo + " » : droits insuffisants sur le VPS "
                            + "(souvent /opt/vps-autopilot/platforms en 700 root).",
                    where,
                    "Relance Déployer : le rôle corrige maintenant les droits pour deploy. "
                            + "Sinon en SSH : sudo chown deploy:deploy /opt/vps-autopilot/platforms "
                            + "&& sudo chmod 755 /opt/vps-autopilot/platforms. "
                            + (serviceId == null ? "" : "Puis « Déployer " + serviceId + " ».")));
        }
        throw new IllegalStateException(OperatorMessage.of(
                "clone impossible de « " + repo + " » (branche « " + gitRef + " »)"
                        + (serviceId == null ? "." : " — service « " + serviceId + " »."),
                where,
                "Vérifie Deploy key lecture seule pour ce dépôt, ou HTTPS public. "
                        + (serviceId == null ? "Puis Déployer." : "Puis « Déployer " + serviceId + " » seulement. ")
                        + detailFromRemote(raw)));
    }

    private static String matchGroup(Pattern p, String raw) {
        Matcher m = p.matcher(raw);
        return m.find() ? m.group(1).strip() : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
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
                    nextActionForApp(msg, app.id())), e);
        }
        return new IllegalStateException(OperatorMessage.of(
                "commande distante en échec (exit ≠ 0).",
                "Étape Déployer — " + app.id(),
                "Détail : " + abbreviate(msg, 320)), e);
    }

    static IllegalStateException unknownPlatform(IOException e, PlatformSpec platform, String onlyServiceId) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        throwIfKnownPlatform(msg, platform, onlyServiceId);
        return new IllegalStateException(OperatorMessage.of(
                "commande distante en échec (exit ≠ 0).",
                "Déployer plateforme — " + platform.id()
                        + (onlyServiceId == null || onlyServiceId.isBlank() ? "" : " / " + onlyServiceId),
                "Regarde Activité / logs du job. Si un service précis a échoué : corrige son dépôt, pousse, "
                        + "puis clique « Déployer <service> » (pas « Tout déployer »). Détail : "
                        + abbreviate(msg, 280)), e);
    }

    private static void throwCommonInfra(String raw, String gitUrl, String gitRef) {
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
                    "Étape Déployer — dépôt " + (gitUrl == null ? "" : gitUrl),
                    "ajoute un Dockerfile à la racine de la branche « " + (gitRef == null ? "main" : gitRef)
                            + " », pousse sur GitHub, puis Déployer — ou confirme le rattrapage template."));
        }
        if (raw.contains("NEED_GIT_DEPLOY_KEY")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "clone SSH sans clé Git lecture seule.",
                    "Étape Déployer — clé Git",
                    "Génère la clé dans la console, ajoute la publique en Deploy key read-only, puis Déployer."));
        }
        if (raw.contains("GIT_CLONE_FAILED")) {
            String repo = gitUrl == null ? "dépôt" : gitUrl;
            String ref = gitRef == null ? "main" : gitRef;
            throw new IllegalStateException(OperatorMessage.of(
                    "clone impossible de « " + repo + " » (branche « " + ref + " »).",
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
    }

    static String resolveFailedService(String raw, Set<String> knownIds, String onlyServiceId) {
        if (onlyServiceId != null && !onlyServiceId.isBlank()) {
            return onlyServiceId.trim().toLowerCase(Locale.ROOT);
        }
        Matcher marked = BUILD_FAILED_SERVICE.matcher(raw);
        if (marked.find()) {
            return marked.group(1);
        }
        Matcher only = ONLY_SERVICE.matcher(raw);
        if (only.find() && (knownIds.isEmpty() || knownIds.contains(only.group(1)))) {
            return only.group(1);
        }
        // Dernière cible compose mentionnée juste avant l’échec (BuildKit).
        String lastNearError = null;
        Matcher m = COMPOSE_TARGET.matcher(raw);
        while (m.find()) {
            String id = m.group(1);
            if (!knownIds.isEmpty() && !knownIds.contains(id)) {
                continue;
            }
            int from = Math.max(0, m.start() - 80);
            int to = Math.min(raw.length(), m.end() + 400);
            String window = raw.substring(from, to).toLowerCase(Locale.ROOT);
            if (window.contains("error") || window.contains("failed") || window.contains("cannot find symbol")
                    || window.contains("compilation failure")) {
                lastNearError = id;
            } else if (lastNearError == null) {
                lastNearError = id;
            }
        }
        // Préférer la cible dans le bloc « > [svc …] » final (résumé BuildKit).
        Matcher summary = Pattern.compile("(?m)^\\s*> \\[([a-z][a-z0-9-]{0,31}) ").matcher(raw);
        String lastSummary = null;
        while (summary.find()) {
            String id = summary.group(1);
            if (knownIds.isEmpty() || knownIds.contains(id)) {
                lastSummary = id;
            }
        }
        if (lastSummary != null) {
            return lastSummary;
        }
        return lastNearError;
    }

    static String classifyBuild(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("compilation failure") || lower.contains("cannot find symbol")
                || lower.contains("mvn ") || lower.contains("maven-compiler")) {
            return "Maven / Java";
        }
        if (lower.contains("failed to compile") || lower.contains("npm run build")
                || lower.contains("eslint") || lower.contains("type error")) {
            return "Next / npm";
        }
        return "image Docker";
    }

    private static String nextActionForApp(String raw, String id) {
        String kind = classifyBuild(raw);
        if ("Maven / Java".equals(kind)) {
            return "Corrige la compilation Java du dépôt (voir Cause), pousse sur Git, puis reclique Déployer « "
                    + id + " ». Pas besoin de tout réinstaller.";
        }
        if ("Next / npm".equals(kind)) {
            return "Corrige le build Next/TypeScript du dépôt (voir Cause), pousse, puis Déployer « "
                    + id + " ». Le template Next ignore ESLint pendant le build cargo.";
        }
        return "Corrige le dépôt, pousse, puis Déployer « " + id + " ».";
    }

    private static String nextActionForPlatform(PlatformSpec platform, PlatformServiceSpec svc,
                                                 String failedId, String kind) {
        if (failedId == null || failedId.isBlank()) {
            return "Identifie le service en erreur dans le détail Cause. Corrige son dépôt, pousse, "
                    + "puis clique « Déployer <service> » sur la ligne « " + platform.id()
                    + " » — pas « Tout déployer » si un seul service a cassé.";
        }
        String repo = svc == null || svc.gitUrl() == null || svc.gitUrl().isBlank()
                ? "le dépôt Git de « " + failedId + " »"
                : "le dépôt « " + shortRepo(svc.gitUrl()) + " » (service « " + failedId + " »)";
        StringBuilder next = new StringBuilder();
        next.append("1) Corrige ").append(repo);
        if ("Maven / Java".equals(kind)) {
            next.append(" (erreur de compilation Java)");
        } else if ("Next / npm".equals(kind)) {
            next.append(" (build Next/npm)");
        }
        next.append(". 2) Pousse sur Git. 3) Dans le cockpit, sur la plateforme « ")
                .append(platform.id())
                .append(" », clique uniquement « Déployer ")
                .append(failedId)
                .append(" » — ne clique pas « Tout déployer » (les autres services n’ont pas besoin d’être reconstruits).");
        return next.toString();
    }

    private static String shortRepo(String gitUrl) {
        String u = gitUrl.strip();
        int slash = Math.max(u.lastIndexOf('/'), u.lastIndexOf(':'));
        if (slash >= 0 && slash < u.length() - 1) {
            String name = u.substring(slash + 1);
            return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
        }
        return u;
    }

    private static String buildCauseSuffix(String raw) {
        Optional<String> maven = mavenHint(raw);
        if (maven.isPresent()) {
            return " — " + maven.get();
        }
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

    private static Optional<String> mavenHint(String raw) {
        Matcher m = MAVEN_SYMBOL.matcher(raw);
        if (!m.find()) {
            return Optional.empty();
        }
        String file = m.group(1);
        String line = m.group(2);
        String shortFile = file.substring(file.lastIndexOf('/') + 1);
        String after = raw.substring(m.end());
        Matcher sym = MAVEN_SYMBOL_NAME.matcher(after);
        String method = sym.find() ? sym.group(1) : "?";
        Matcher loc = MAVEN_LOCATION_TYPE.matcher(after);
        if (loc.find()) {
            String typeFqcn = loc.group(1);
            String shortType = typeFqcn.substring(typeFqcn.lastIndexOf('.') + 1);
            return Optional.of(shortFile + ":" + line + " — appelle « " + method
                    + " » absente de « " + shortType + " » (classe " + shortType
                    + " : ajouter le getter, ou corriger l’appel dans " + shortFile + ")");
        }
        return Optional.of(shortFile + ":" + line + " — méthode manquante « " + method + " »");
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
