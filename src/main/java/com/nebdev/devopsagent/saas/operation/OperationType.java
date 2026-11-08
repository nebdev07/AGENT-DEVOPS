package com.nebdev.devopsagent.saas.operation;

import com.nebdev.devopsagent.saas.organization.OrgPermissions;

import java.util.Arrays;
import java.util.Optional;

/**
 * Catalogue fermé des opérations exécutables (liste blanche, ADR-050). Chaque type correspond à un rôle
 * versionné du moteur : aucune commande libre, ni d'un utilisateur ni d'une IA.
 * DESTRUCTIVE n'a aucun type à ce jour : toute demande est refusée tant qu'une politique dédiée n'existe pas.
 */
public enum OperationType {

    DISCOVER(Risk.READ, OrgPermissions.SERVER_ANALYZE, null, "Analyse du serveur (lecture seule)"),
    INSTALL_SECURITY(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, "security", "Sécurité de base (UFW, fail2ban, SSH durci)"),
    INSTALL_DOCKER(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, "docker", "Docker Engine + Compose"),
    INSTALL_TRAEFIK(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, "traefik", "Traefik + HTTPS Let's Encrypt"),
    INSTALL_MARIADB(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, "mariadb", "MariaDB interne"),
    INSTALL_REDIS(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, "redis", "Redis interne"),
    INSTALL_DEPLOY_USER(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, "deploy", "Compte deploy (Docker, sans sudo)"),
    INSTALL_BACKUPS(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, "backups", "Sauvegardes chiffrées + exercice mensuel"),
    RUN_BACKUP(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, null, "Sauvegarde immédiate"),
    ROTATE_CREDENTIAL(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, null, "Rotation de la clé SSH du serveur"),
    /** Déploiement d'une plateforme N services (spec figée dans les paramètres de l'opération). */
    DEPLOY_PLATFORM(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, null, "Déployer une plateforme multi-services"),
    /** Déploiement d'une application cargo 1:1. */
    DEPLOY_APP(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, null, "Déployer une application"),
    /** Console SQL lecture seule sur la base d'une plateforme. */
    PLATFORM_SQL_READ(Risk.READ, OrgPermissions.SERVER_ANALYZE, null, "SQL plateforme (lecture)"),
    /** Console SQL écriture (sauvegarde préalable côté rôle). */
    PLATFORM_SQL_WRITE(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, null, "SQL plateforme (écriture)"),
    /** Restauration dump SQL / archive fichiers (fichiers en staging one-shot). */
    RESTORE_PLATFORM(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, null, "Restaurer les données d'une plateforme"),
    /** Pose un secret d'exécution (valeur hors paramètres, staging one-shot). */
    SET_PLATFORM_SECRET(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, null, "Poser un secret d'exécution"),
    /** Retire un secret d'exécution. */
    UNSET_PLATFORM_SECRET(Risk.CHANGE, OrgPermissions.OPERATION_PROPOSE, null, "Retirer un secret d'exécution");

    public enum Risk { READ, CHANGE, DESTRUCTIVE }

    private final Risk risk;
    private final String proposePermission;
    private final String component;
    private final String label;

    OperationType(Risk risk, String proposePermission, String component, String label) {
        this.risk = risk;
        this.proposePermission = proposePermission;
        this.component = component;
        this.label = label;
    }

    public Risk risk() { return risk; }
    public String proposePermission() { return proposePermission; }
    /** Composant du moteur ({@code roles/<component>/apply.sh}) pour les installations. */
    public String component() { return component; }
    public String label() { return label; }
    public boolean autoApproved() { return risk == Risk.READ; }

    public static Optional<OperationType> parse(String raw) {
        return Arrays.stream(values()).filter(t -> t.name().equalsIgnoreCase(raw == null ? "" : raw.strip())).findFirst();
    }
}
