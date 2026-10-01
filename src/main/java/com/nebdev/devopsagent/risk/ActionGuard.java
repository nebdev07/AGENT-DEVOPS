package com.nebdev.devopsagent.risk;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * READ passe. CHANGE : whitelist + confirmation explicite (ADR-016).
 * DESTRUCTIVE : toujours refusé tant que le module n'existe pas.
 */
@Component
public class ActionGuard {

    static final Set<String> WHITELISTED_CHANGE = Set.of(
            "install-agent-pubkey",
            "install-security",
            "install-docker",
            "install-traefik",
            "install-mariadb",
            "install-deploy",
            "install-redis",
            "install-backups",
            "deploy-app",
            "deploy-platform",
            "restore-platform",
            "sql-write-platform"
    );

    public void assertAllowed(RiskLevel level, String action) {
        if (level == RiskLevel.READ) {
            return;
        }
        throw new UnsafeActionException(
                "Action refusée (" + level + "): " + action + ". CHANGE exige assertConfirmedChange.");
    }

    public void assertConfirmedChange(String action, boolean confirmed) {
        if (!WHITELISTED_CHANGE.contains(action)) {
            throw new UnsafeActionException("CHANGE non whitelisté: " + action);
        }
        if (!confirmed) {
            throw new UnsafeActionException(
                    "CHANGE non confirmé: " + action
                            + ". La console doit afficher le plan et exiger une confirmation.");
        }
    }
}
