package com.nebdev.devopsagent.risk;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class ForbiddenTargetGuard {

    private static final Set<String> BLOCKED = Set.of(
            "54.37.156.11",
            "arc-nwanyo.org"
    );

    /** Serveurs en cours de reprise (ADR-037) : analyse permise, aucune modification. */
    private static final Set<String> READ_ONLY = Set.of(
            "154.0.30.233"
    );

    /** Toute action qui modifie le serveur (installation, déploiement, restauration, SQL, clé SSH). */
    public void assertAllowed(String host) {
        String key = normalize(host);
        if (key == null) {
            return;
        }
        if (BLOCKED.contains(key)) {
            throw new UnsafeActionException("Cette cible n’est pas autorisée : " + host);
        }
        if (READ_ONLY.contains(key)) {
            throw new UnsafeActionException("Cette cible est en lecture seule (analyse uniquement) : " + host
                    + ". Pour y poser la clé de l’agent sans partager d’accès : Aide › 3. Clé SSH › méthode D.");
        }
    }

    /** Actions sans écriture sur le serveur : analyse, lecture de l’empreinte SSH. */
    public void assertReadAllowed(String host) {
        String key = normalize(host);
        if (key != null && BLOCKED.contains(key)) {
            throw new UnsafeActionException("Cette cible n’est pas autorisée : " + host);
        }
    }

    private static String normalize(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        return host.trim().toLowerCase(Locale.ROOT);
    }
}
