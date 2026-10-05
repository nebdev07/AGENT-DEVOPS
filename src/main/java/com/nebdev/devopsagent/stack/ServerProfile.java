package com.nebdev.devopsagent.stack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Réglages propres à un serveur, choisis par l'opérateur (Mes serveurs).
 *
 * @param httpOnly         pas de HTTPS public possible (réseau interne, NAT sans 443) : Traefik sans redirection ni certificat
 * @param publicPort       port public vu par les utilisateurs quand la passerelle le traduit (ex. 91 → 80) ; 0 = port standard
 * @param sharedServer serveur partagé : la sécurité ne coupe pas les connexions SSH par mot de passe des autres comptes
 */
public record ServerProfile(boolean httpOnly, int publicPort, boolean sharedServer) {

    public static final ServerProfile DEFAULT = new ServerProfile(false, 0, false);

    static final List<String> SECURITY_ALL = List.of("ufw", "fail2ban", "ssh-harden", "unattended");
    static final String TRAEFIK_HTTP_ONLY = "http-only";

    public ServerProfile {
        if (publicPort < 0 || publicPort > 65535) {
            throw new IllegalArgumentException("Port public hors plage (1–65535, ou vide).");
        }
    }

    /** Détails de sécurité réellement voulus sur ce serveur ; une liste vide de la pile veut dire « tous ». */
    Set<String> securityWanted(List<String> selected) {
        Set<String> wanted = new LinkedHashSet<>(selected == null || selected.isEmpty() ? SECURITY_ALL : selected);
        if (sharedServer) {
            wanted.remove("ssh-harden");
        }
        return wanted;
    }

    /** Détails envoyés au script d'un rôle : explicites dès que le profil s'écarte des valeurs par défaut. */
    List<String> roleDetails(String componentId, List<String> selected) {
        if ("security".equals(componentId) && sharedServer) {
            return List.copyOf(securityWanted(selected));
        }
        if ("traefik".equals(componentId) && httpOnly) {
            return List.of("proxy", TRAEFIK_HTTP_ONLY);
        }
        return selected == null ? List.of() : selected;
    }

    String scheme() {
        return httpOnly ? "http" : "https";
    }

    /** Hôte tel que tapé dans le navigateur, avec le port public s'il n'est pas le port standard du schéma. */
    String authority(String hostname) {
        int standard = httpOnly ? 80 : 443;
        return publicPort == 0 || publicPort == standard ? hostname : hostname + ":" + publicPort;
    }
}
