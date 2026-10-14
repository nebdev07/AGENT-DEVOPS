package com.nebdev.devopsagent.journal;

import java.util.Locale;

/**
 * Actions journalisées : toute requête qui modifie quelque chose (serveur ou poste) et la génération
 * du rapport client. Les lectures de la console (GET) ne sont pas journalisées.
 */
public final class ActionCatalog {

    /** READ = lecture du serveur, LOCAL = réglage sur ce poste, CHANGE = modification du serveur. */
    public enum Risk {
        READ("Lecture"),
        LOCAL("Poste"),
        CHANGE("Modification");

        private final String label;

        Risk(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Action(String label, Risk risk) {}

    private ActionCatalog() {}

    /** @return l’action journalisée, ou {@code null} si la requête n’est pas journalisée */
    public static Action classify(String method, String path) {
        if (method == null || path == null) {
            return null;
        }
        String m = method.toUpperCase(Locale.ROOT);
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        if (!p.startsWith("/api/v1/")) {
            return null;
        }
        return switch (m + " " + p) {
            case "POST /api/v1/discover" -> new Action("Analyse du serveur", Risk.READ);
            case "POST /api/v1/ssh/accept-host" -> new Action("Empreinte SSH du serveur acceptée", Risk.LOCAL);
            case "POST /api/v1/ssh/install-key" -> new Action("Clé de l’agent posée sur le serveur", Risk.CHANGE);
            case "POST /api/v1/install" -> new Action("Installation d’un composant", Risk.CHANGE);
            case "GET /api/v1/audit/report" -> new Action("Rapport d’audit client généré", Risk.READ);
            case "POST /api/v1/backups/run" -> new Action("Sauvegarde lancée sur le serveur", Risk.CHANGE);
            case "POST /api/v1/backups/fetch-latest" -> new Action("Copie de la dernière sauvegarde sur ce poste", Risk.READ);
            case "POST /api/v1/backups/verify" -> new Action("Vérification d’une copie locale", Risk.LOCAL);
            case "POST /api/v1/backups/drill" -> new Action("Exercice de restauration", Risk.CHANGE);
            case "POST /api/v1/backups/restore-platform" -> new Action("Restauration depuis une sauvegarde", Risk.CHANGE);
            case "POST /api/v1/platforms/deploy" -> new Action("Déploiement d’une plateforme", Risk.CHANGE);
            case "POST /api/v1/platforms/restore" -> new Action("Restauration de données fournies", Risk.CHANGE);
            case "POST /api/v1/platforms/sql/analyze" -> new Action("Analyse d’une requête SQL", Risk.LOCAL);
            case "POST /api/v1/platforms/sql" -> new Action("Requête SQL sur une plateforme", Risk.CHANGE);
            case "POST /api/v1/platforms/secrets" -> new Action("Secret d’exécution posé", Risk.CHANGE);
            case "POST /api/v1/platforms/secrets/remove" -> new Action("Secret d’exécution retiré", Risk.CHANGE);
            case "PUT /api/v1/platforms" -> new Action("Liste des plateformes enregistrée", Risk.LOCAL);
            case "DELETE /api/v1/platforms" -> new Action("Plateforme retirée de la liste", Risk.LOCAL);
            case "POST /api/v1/apps/deploy" -> new Action("Déploiement d’une application", Risk.CHANGE);
            case "PUT /api/v1/apps" -> new Action("Liste des applications enregistrée", Risk.LOCAL);
            case "DELETE /api/v1/apps" -> new Action("Application retirée de la liste", Risk.LOCAL);
            case "PUT /api/v1/apps/public-domain" -> new Action("Domaine public enregistré", Risk.LOCAL);
            case "PUT /api/v1/servers/profile" -> new Action("Profil du serveur enregistré", Risk.LOCAL);
            case "DELETE /api/v1/servers" -> new Action("Serveur oublié sur ce poste", Risk.LOCAL);
            case "PUT /api/v1/stack" -> new Action("Choix de la pile enregistré", Risk.LOCAL);
            case "POST /api/v1/git-deploy-key" -> new Action("Clé de déploiement Git générée", Risk.LOCAL);
            case "POST /api/v1/git-deploy-key/repos" -> new Action("Dépôt Git enregistré", Risk.LOCAL);
            case "POST /api/v1/git-deploy-key/repos/verify" -> new Action("Accès au dépôt Git vérifié", Risk.READ);
            default -> m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS")
                    ? null
                    : new Action(m + " " + p, Risk.CHANGE);
        };
    }
}
