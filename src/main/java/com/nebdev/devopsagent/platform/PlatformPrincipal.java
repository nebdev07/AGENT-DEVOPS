package com.nebdev.devopsagent.platform;

import java.util.Set;

/** Compte authentifié pour la durée de la requête. */
public record PlatformPrincipal(
        String accountId,
        String email,
        String displayName,
        String roleId,
        Set<String> permissions,
        boolean mustChangePassword
) {
    public boolean has(Permission permission) {
        return permissions.contains(permission.code());
    }

    public boolean isPlatformAdmin() {
        return has(Permission.PLATFORM_ADMIN);
    }
}
