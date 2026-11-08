package com.nebdev.devopsagent.platform;

import java.util.Locale;
import java.util.Optional;

/**
 * Associe méthode + chemin API → permission (et si un serveur est exigé).
 *
 * <p>Règles (correctifs d’audit 2026-11-07, S1–S4) :
 * <ul>
 *   <li>Une route {@code serverScoped} sans hôte identifiable est refusée aux non-admins (fail-closed),
 *       voir {@link PlatformRbacFilter}.</li>
 *   <li>Les intentions globales (pile, apps, plateformes, domaine public) ne se modifient qu’en
 *       {@code platform.admin} : elles sont partagées par toute l’instance.</li>
 *   <li>Toute lecture qui renvoie l’état d’un serveur précis est scopée à ce serveur.</li>
 * </ul>
 */
public final class RoutePermissions {

    public record Rule(Permission permission, boolean serverScoped) {}

    private RoutePermissions() {}

    public static Optional<Rule> match(String method, String path) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String p = path == null ? "" : path;
        boolean read = "GET".equals(m) || "HEAD".equals(m);
        if (!p.startsWith("/api/v1/")) {
            return Optional.empty();
        }
        if (p.startsWith("/api/v1/platform/auth/request") || p.startsWith("/api/v1/platform/auth/consume")
                || p.startsWith("/api/v1/platform/auth/login")
                || p.startsWith("/api/v1/platform/auth/forgot-password")
                || p.startsWith("/api/v1/platform/auth/reset-password")) {
            return Optional.empty();
        }
        if (p.startsWith("/api/v1/platform/auth/me") || p.startsWith("/api/v1/platform/auth/logout")
                || p.startsWith("/api/v1/platform/auth/password")
                || p.equals("/api/v1/platform/permissions") || p.equals("/api/v1/platform/grants/mine")) {
            return Optional.empty(); // session suffit
        }
        if (p.startsWith("/api/v1/platform/accounts")) {
            return Optional.of(new Rule(Permission.ACCOUNT_MANAGE, false));
        }
        if (p.startsWith("/api/v1/platform/roles")) {
            return Optional.of(new Rule(Permission.ROLE_MANAGE, false));
        }
        if (p.startsWith("/api/v1/platform/grants")) {
            return Optional.of(new Rule(Permission.SERVER_ASSIGN, false));
        }
        // S1 : dernier snapshot tous serveurs confondus → admin seulement.
        if (p.equals("/api/v1/state")) {
            return Optional.of(new Rule(Permission.PLATFORM_ADMIN, false));
        }
        if (p.startsWith("/api/v1/discover") || p.startsWith("/api/v1/ssh/")) {
            return Optional.of(new Rule(read ? Permission.SERVER_READ : Permission.SERVER_ANALYZE, true));
        }
        if (p.startsWith("/api/v1/install") || p.startsWith("/api/v1/plan")) {
            return Optional.of(new Rule(read ? Permission.SERVER_READ : Permission.SERVER_CHANGE_INSTALL, true));
        }
        if (p.startsWith("/api/v1/stack")) {
            // Pile voulue = intention globale de l’instance.
            return Optional.of(new Rule(read ? Permission.SERVER_READ : Permission.PLATFORM_ADMIN, false));
        }
        if (p.startsWith("/api/v1/notify")) {
            return Optional.of(new Rule(read ? Permission.SERVER_READ : Permission.SERVER_CHANGE_NOTIFY, true));
        }
        if (p.startsWith("/api/v1/backups")) {
            return Optional.of(new Rule(read ? Permission.SERVER_READ : Permission.SERVER_CHANGE_BACKUP, true));
        }
        if (p.startsWith("/api/v1/deploy/")) {
            return Optional.of(new Rule(Permission.SERVER_READ, true));
        }
        if (p.startsWith("/api/v1/platforms") && p.contains("secret")) {
            return Optional.of(new Rule(read ? Permission.SERVER_READ : Permission.SERVER_CHANGE_SECRET, true));
        }
        if (p.startsWith("/api/v1/platforms/jobs/") || p.startsWith("/api/v1/apps/jobs/")) {
            return Optional.of(new Rule(Permission.SERVER_READ, true));
        }
        if (p.equals("/api/v1/platforms/deploy") || p.equals("/api/v1/apps/deploy")
                || p.startsWith("/api/v1/platforms/restore") || p.startsWith("/api/v1/platforms/sql")) {
            return Optional.of(new Rule(Permission.SERVER_CHANGE_DEPLOY, true));
        }
        if (p.startsWith("/api/v1/platforms") || p.startsWith("/api/v1/apps")) {
            // Lecture des fiches (plan, statut public HTTPS) ; écriture = intention globale (S3).
            return Optional.of(new Rule(read ? Permission.SERVER_READ : Permission.PLATFORM_ADMIN, false));
        }
        if (p.startsWith("/api/v1/audit")) {
            return Optional.of(new Rule(Permission.AUDIT_READ, true));
        }
        if (p.startsWith("/api/v1/journal")) {
            Permission perm = "POST".equals(m) ? Permission.SERVER_CHANGE_NOTIFY : Permission.SERVER_JOURNAL;
            return Optional.of(new Rule(perm, true));
        }
        if (p.startsWith("/api/v1/servers/last")) {
            return Optional.of(new Rule(Permission.SERVER_READ, true));
        }
        if (p.startsWith("/api/v1/servers")) {
            if ("DELETE".equals(m) || p.contains("profile")) {
                return Optional.of(new Rule(Permission.SERVER_ASSIGN, false));
            }
            return Optional.of(new Rule(Permission.SERVER_READ, false));
        }
        // Autres API : lecture minimale si connecté ; écriture = admin plateforme
        if (read) {
            return Optional.of(new Rule(Permission.SERVER_READ, false));
        }
        return Optional.of(new Rule(Permission.PLATFORM_ADMIN, false));
    }

    /** Identifiant de tâche longue si le chemin est un suivi de tâche, sinon vide. */
    public static Optional<String> jobId(String path) {
        if (path == null) {
            return Optional.empty();
        }
        for (String prefix : new String[]{"/api/v1/platforms/jobs/", "/api/v1/apps/jobs/"}) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                return Optional.of(path.substring(prefix.length()));
            }
        }
        return Optional.empty();
    }
}
