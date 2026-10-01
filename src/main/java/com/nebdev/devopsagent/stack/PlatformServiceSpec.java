package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Un service d’une plateforme cargo (ADR-036). Pas de CHANGE ici.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformServiceSpec(
        String id,
        String gitUrl,
        String gitRef,
        String hostname,
        int port,
        String context,
        List<String> needs,
        Map<String, String> buildArgs,
        String pathPrefix,
        String template,
        Map<String, String> env,
        boolean keepPath
) {

    public PlatformServiceSpec {
        pathPrefix = pathPrefix == null ? "" : pathPrefix;
        needs = needs == null ? List.of() : needs;
        buildArgs = buildArgs == null ? Map.of() : buildArgs;
        template = template == null ? "" : template;
        env = env == null ? Map.of() : env;
    }

    public PlatformServiceSpec(
            String id,
            String gitUrl,
            String gitRef,
            String hostname,
            int port,
            String context,
            List<String> needs,
            Map<String, String> buildArgs,
            String pathPrefix
    ) {
        this(id, gitUrl, gitRef, hostname, port, context, needs, buildArgs, pathPrefix, "", Map.of(), false);
    }

    public static final String NEED_REDIS_PRIVATE = "redis-private";
    /** ASCII : survit aux clients qui décodent mal l’UTF-8 (PowerShell 5). */
    public static final String SECRET_MASK = "********";
    public static final String HOST_PLACEHOLDER = "{{HOST}}";
    static final Set<String> TEMPLATES = Set.of("next", "node", "spring-boot-11", "spring-boot-17", "spring-boot-21");
    private static final Pattern ENV_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(PASSWORD|SECRET|TOKEN|PRIVATE|API_KEY|_PWD).*");

    public static boolean secretKey(String key) {
        return key != null && SECRET_KEY.matcher(key).matches();
    }

    public boolean templated() {
        return !template.isBlank();
    }

    public boolean stripPath(boolean pathRouting) {
        return pathRouting && !keepPath && !pathPrefix.isBlank() && !"/".equals(pathPrefix);
    }

    /**
     * Copie pour le serveur : {@value #HOST_PLACEHOLDER} remplacé par le nom d’hôte. La fiche garde le marqueur,
     * pour qu’un changement de domaine ne demande que Enregistrer + Déployer.
     */
    public PlatformServiceSpec resolved() {
        return new PlatformServiceSpec(id, gitUrl, gitRef, hostname, port, context, needs,
                withHost(buildArgs, hostname), pathPrefix, template, withHost(env, hostname), keepPath);
    }

    /** Copie pour l’API : valeurs sensibles masquées. */
    public PlatformServiceSpec masked() {
        if (env.isEmpty()) {
            return this;
        }
        Map<String, String> out = new LinkedHashMap<>();
        env.forEach((k, v) -> out.put(k, secretKey(k) && v != null && !v.isEmpty() ? SECRET_MASK : v));
        return new PlatformServiceSpec(id, gitUrl, gitRef, hostname, port, context, needs, buildArgs, pathPrefix,
                template, Map.copyOf(out), keepPath);
    }

    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9._/-]{1,80}$");
    private static final Pattern HOST = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");
    private static final Pattern CONTEXT = Pattern.compile("^[A-Za-z0-9._/-]{0,120}$");
    private static final Pattern BUILD_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{0,47}$");
    private static final Pattern VOLUME = Pattern.compile("^volume:[a-z][a-z0-9-]{0,31}$");
    private static final Pattern PATH = Pattern.compile("^/([a-z][a-z0-9-]{0,47})?$");

    public static PlatformServiceSpec normalized(
            String platformId,
            String baseDomain,
            String vpsIp,
            String id,
            String gitUrl,
            String gitRef,
            String hostname,
            Integer port,
            String context,
            List<String> needs,
            Map<String, String> buildArgs,
            String pathPrefix,
            boolean pathRouting,
            String template,
            Map<String, String> env,
            boolean keepPath
    ) {
        String where = "Bande Plateformes — service";
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "un service n’a pas d’identifiant.",
                    where,
                    "ex. api, www, admin (a-z, chiffres, tirets)."));
        }
        String slug = id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "identifiant de service « " + abbreviate(id) + " » invalide.",
                    where + " · Identifiant",
                    "commence par une lettre ; a-z, 0-9, tirets ; max 32 car."));
        }
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "Git absent pour le service « " + slug + " ».",
                    where + " · Git",
                    "HTTPS public ou git@github.com:org/repo.git (clé lecture seule)."));
        }
        String git = GitUrls.normalize(gitUrl);
        String ref = (gitRef == null || gitRef.isBlank()) ? "main" : gitRef.trim();
        if (!REF.matcher(ref).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "branche « " + abbreviate(ref) + " » invalide (service " + slug + ").",
                    where + " · Branche",
                    "ex. main"));
        }
        int p = port == null ? 8080 : port;
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "port " + p + " hors plage (service " + slug + ").",
                    where + " · Port",
                    "port écouté dans le conteneur, 1–65535."));
        }
        String ctx = context == null ? "" : context.trim().replace('\\', '/');
        if (ctx.startsWith("/")) {
            ctx = ctx.substring(1);
        }
        if (!ctx.isEmpty() && (ctx.contains("..") || !CONTEXT.matcher(ctx).matches())) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "sous-dossier Docker « " + abbreviate(ctx) + " » invalide.",
                    where + " · Context (monorepo)",
                    "ex. frontend/site ou backend/api — pas de .."));
        }
        String host = hostname == null ? "" : hostname.trim().toLowerCase(Locale.ROOT);
        if (host.isBlank()) {
            host = pathRouting
                    ? derivePlatformHost(platformId, baseDomain, vpsIp)
                    : deriveHost(slug, platformId, baseDomain, vpsIp);
        }
        if (host.startsWith("http://") || host.startsWith("https://") || host.contains("github.com") || host.contains("/")) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "hôte du service « " + slug + " » ressemble à une URL.",
                    where + " · Nom d’hôte",
                    "DNS seulement, ex. api.ma-plateforme.apps.exemple.com"));
        }
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "hôte « " + abbreviate(host) + " » invalide (service " + slug + ").",
                    where + " · Nom d’hôte",
                    "remplis le domaine de la plateforme, ou un DNS complet par service."));
        }
        List<String> needList = normalizeNeeds(slug, needs);
        Map<String, String> args = normalizeBuildArgs(slug, buildArgs);
        String path = pathRouting ? normalizePath(slug, pathPrefix) : "";
        String tpl = normalizeTemplate(slug, template);
        Map<String, String> envMap = normalizeEnv(slug, env);
        boolean keep = pathRouting && keepPath && !"/".equals(path);
        return new PlatformServiceSpec(slug, git, ref, host, p, ctx, needList, args, path, tpl, envMap, keep);
    }

    private static Map<String, String> withHost(Map<String, String> values, String host) {
        if (values.values().stream().noneMatch(v -> v.contains(HOST_PLACEHOLDER))) {
            return values;
        }
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k, v.replace(HOST_PLACEHOLDER, host)));
        return Map.copyOf(out);
    }

    private static String normalizeTemplate(String slug, String template) {
        String t = template == null ? "" : template.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || "none".equals(t) || "repo".equals(t)) {
            return "";
        }
        if (!TEMPLATES.contains(t)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "modèle Dockerfile « " + abbreviate(template) + " » inconnu (service " + slug + ").",
                    "Bande Plateformes — Dockerfile",
                    "vide (Dockerfile du dépôt), next, node, spring-boot-11, spring-boot-17 ou spring-boot-21."));
        }
        return t;
    }

    private static Map<String, String> normalizeEnv(String slug, Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return Map.of();
        }
        if (env.size() > 60) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "plus de 60 variables d’exécution (service " + slug + ").",
                    "Bande Plateformes — variables",
                    "garde l’essentiel ; le reste dans svc-" + slug + ".local.env sur le VPS."));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }
            String key = e.getKey().trim();
            if (!ENV_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "variable « " + abbreviate(key) + " » invalide (service " + slug + ").",
                        "Bande Plateformes — variables",
                        "MAJUSCULES_ET_CHIFFRES, ex. SPRING_PROFILES_ACTIVE"));
            }
            String val = e.getValue() == null ? "" : e.getValue();
            if (val.length() > 1024 || val.contains("\n") || val.contains("\r")) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "valeur de " + key + " invalide (service " + slug + ").",
                        "Bande Plateformes — variables",
                        "une ligne, max 1024 caractères."));
            }
            out.put(key, val);
        }
        return Map.copyOf(out);
    }

    static String derivePlatformHost(String platformId, String baseDomain, String vpsIp) {
        String base = baseDomain == null ? "" : baseDomain.trim().toLowerCase(Locale.ROOT);
        if (!base.isEmpty()) {
            return platformId + "." + base;
        }
        if (vpsIp != null && vpsIp.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return platformId + "." + vpsIp + ".sslip.io";
        }
        throw new IllegalArgumentException(OperatorMessage.of(
                "impossible de proposer un DNS pour la plateforme « " + platformId + " ».",
                "Bande Plateformes — domaine ou hôte",
                "saisis un domaine de base, ou analyse une IP (sslip), ou un nom d’hôte commun."));
    }

    static String derivePath(String serviceId) {
        return switch (serviceId) {
            case "www", "site", "front", "web" -> "/";
            default -> "/" + serviceId;
        };
    }

    private static String normalizePath(String slug, String pathPrefix) {
        String raw = pathPrefix == null ? "" : pathPrefix.trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) {
            raw = derivePath(slug);
        }
        if (!raw.startsWith("/")) {
            raw = "/" + raw;
        }
        if (raw.length() > 1 && raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.contains("..") || raw.contains("//") || raw.contains("\\")) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "chemin « " + abbreviate(pathPrefix) + " » invalide (service " + slug + ").",
                    "Bande Plateformes — Chemin URL",
                    "un seul segment, ex. /api ou / (site). Pas de /api/v1."));
        }
        if (!PATH.matcher(raw).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "chemin « " + abbreviate(raw) + " » invalide (service " + slug + ").",
                    "Bande Plateformes — Chemin URL",
                    "ex. / , /api , /admin — a-z, chiffres, tirets."));
        }
        return raw;
    }

    static String deriveHost(String serviceId, String platformId, String baseDomain, String vpsIp) {
        String base = baseDomain == null ? "" : baseDomain.trim().toLowerCase(Locale.ROOT);
        if (!base.isEmpty()) {
            return serviceId + "." + platformId + "." + base;
        }
        if (vpsIp != null && vpsIp.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return serviceId + "-" + platformId + "." + vpsIp + ".sslip.io";
        }
        throw new IllegalArgumentException(OperatorMessage.of(
                "impossible de proposer un DNS pour « " + serviceId + " ».",
                "Bande Plateformes — domaine ou hôte du service",
                "saisis un domaine de base (ex. apps.exemple.com) ou un nom d’hôte par service."));
    }

    private static List<String> normalizeNeeds(String slug, List<String> needs) {
        if (needs == null || needs.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : needs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String n = raw.trim().toLowerCase(Locale.ROOT);
            if ("uploads".equals(n) || "files".equals(n)) {
                n = "volume:uploads";
            }
            if ("cache".equals(n)) {
                n = NEED_REDIS_PRIVATE;
            }
            boolean ok = "mariadb".equals(n) || "redis".equals(n) || NEED_REDIS_PRIVATE.equals(n)
                    || VOLUME.matcher(n).matches();
            if (!ok) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "besoin « " + abbreviate(raw) + " » inconnu (service " + slug + ").",
                        "Bande Plateformes — besoins",
                        "coche MariaDB, Redis, ou volume fichiers (uploads). Pas d’Apache / Nginx hôte."));
            }
            if (!out.contains(n)) {
                out.add(n);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, String> normalizeBuildArgs(String slug, Map<String, String> buildArgs) {
        if (buildArgs == null || buildArgs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : buildArgs.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }
            String key = e.getKey().trim();
            if (!BUILD_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "variable de build « " + abbreviate(key) + " » invalide (service " + slug + ").",
                        "Bande Plateformes — build",
                        "MAJUSCULES_ET_CHIFFRES, ex. NEXT_PUBLIC_API_URL"));
            }
            String val = e.getValue() == null ? "" : e.getValue().trim();
            if (val.length() > 512) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "valeur de " + key + " trop longue (service " + slug + ").",
                        "Bande Plateformes — build",
                        "raccourcis l’URL (max 512 car.)."));
            }
            if (!val.isEmpty()) {
                out.put(key, val);
            }
        }
        return Map.copyOf(out);
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String t = value.trim();
        return t.length() <= 64 ? t : t.substring(0, 61) + "…";
    }
}
