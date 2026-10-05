package com.nebdev.devopsagent.risk;

import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Serveurs protégés de l’opérateur, lus dans {@code devops.guard.*} (fichier local non versionné
 * {@code state/agent-local.yml}) : bloqués (aucune action) ou en lecture seule (analyse uniquement).
 */
@Component
public class ForbiddenTargetGuard {

    private final Set<String> blocked;
    private final Set<String> readOnly;

    @Autowired
    public ForbiddenTargetGuard(DevopsProperties properties) {
        this(properties.getGuard().getBlockedHosts(), properties.getGuard().getReadOnlyHosts());
    }

    public ForbiddenTargetGuard(Collection<String> blocked, Collection<String> readOnly) {
        this.blocked = normalizeAll(blocked);
        this.readOnly = normalizeAll(readOnly);
    }

    public ForbiddenTargetGuard() {
        this(List.of(), List.of());
    }

    /** Toute action qui modifie le serveur (installation, déploiement, restauration, SQL, clé SSH). */
    public void assertAllowed(String host) {
        String key = normalize(host);
        if (key == null) {
            return;
        }
        if (blocked.contains(key)) {
            throw new UnsafeActionException("Cette cible n’est pas autorisée : " + host);
        }
        if (readOnly.contains(key)) {
            throw new UnsafeActionException("Cette cible est en lecture seule (analyse uniquement) : " + host
                    + ". Pour y poser la clé de l’agent sans partager d’accès : Aide › 3. Clé SSH › méthode D.");
        }
    }

    /** Actions sans écriture sur le serveur : analyse, lecture de l’empreinte SSH. */
    public void assertReadAllowed(String host) {
        String key = normalize(host);
        if (key != null && blocked.contains(key)) {
            throw new UnsafeActionException("Cette cible n’est pas autorisée : " + host);
        }
    }

    private static Set<String> normalizeAll(Collection<String> hosts) {
        if (hosts == null) {
            return Set.of();
        }
        return hosts.stream()
                .map(ForbiddenTargetGuard::normalize)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        return host.trim().toLowerCase(Locale.ROOT);
    }
}
