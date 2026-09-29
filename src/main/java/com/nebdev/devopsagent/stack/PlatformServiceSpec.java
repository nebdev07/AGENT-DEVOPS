package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        String pathPrefix
) {

    public PlatformServiceSpec {
        pathPrefix = pathPrefix == null ? "" : pathPrefix;
        needs = needs == null ? List.of() : needs;
        buildArgs = buildArgs == null ? Map.of() : buildArgs;
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
            boolean pathRouting
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
        return new PlatformServiceSpec(slug, git, ref, host, p, ctx, needList, args, path);
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
            boolean ok = "mariadb".equals(n) || "redis".equals(n) || VOLUME.matcher(n).matches();
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
