package com.nebdev.devopsagent.platform;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Garde RBAC + assignation serveur (ADR-046).
 * Hors profil {@code platform}, {@link #current()} est vide et les contrôles sont no-op
 * (console locale mono-poste).
 */
@Component
public class AccessControl {

    public static final String ATTR_PRINCIPAL = "platform.principal";
    public static final String ATTR_SESSION_ID = "platform.sessionId";

    private final RoleStore roles;
    private final GrantStore grants;
    private final boolean platformMode;

    public AccessControl(RoleStore roles, GrantStore grants,
                         org.springframework.core.env.Environment environment) {
        this.roles = roles;
        this.grants = grants;
        this.platformMode = java.util.Arrays.asList(environment.getActiveProfiles()).contains("platform");
    }

    public boolean isPlatformMode() {
        return platformMode;
    }

    public Optional<PlatformPrincipal> current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object p = attrs.getAttribute(ATTR_PRINCIPAL, RequestAttributes.SCOPE_REQUEST);
            if (p instanceof PlatformPrincipal principal) {
                return Optional.of(principal);
            }
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
                Object fromReq = servletAttrs.getRequest().getAttribute(ATTR_PRINCIPAL);
                if (fromReq instanceof PlatformPrincipal principal) {
                    return Optional.of(principal);
                }
            }
        }
        return Optional.empty();
    }

    public PlatformPrincipal requireLogin() {
        if (!platformMode) {
            return null;
        }
        return current().orElseThrow(() -> PlatformAccessException.unauthorized("Connexion requise."));
    }

    public PlatformPrincipal require(Permission permission) throws IOException {
        if (!platformMode) {
            return null;
        }
        PlatformPrincipal principal = requireLogin();
        if (!principal.has(permission)) {
            throw PlatformAccessException.forbidden("Permission manquante : " + permission.code());
        }
        return principal;
    }

    public PlatformPrincipal requireServer(String host, int port, String user, Permission permission)
            throws IOException {
        PlatformPrincipal principal = require(permission);
        if (!platformMode || principal == null) {
            return null;
        }
        if (principal.isPlatformAdmin()) {
            return principal;
        }
        if (!grants.hasAccess(principal.accountId(), host, port, user)) {
            throw PlatformAccessException.forbidden("Ce serveur ne vous est pas assigné.");
        }
        return principal;
    }

    /**
     * Serveur connu par son seul hôte (ex. suivi de tâche longue) : il suffit d’un grant sur cet hôte,
     * quel que soit le port / l’utilisateur SSH.
     */
    public PlatformPrincipal requireHost(String host, Permission permission) throws IOException {
        PlatformPrincipal principal = require(permission);
        if (!platformMode || principal == null || principal.isPlatformAdmin()) {
            return principal;
        }
        String h = host == null ? "" : host.strip().toLowerCase(java.util.Locale.ROOT);
        boolean ok = !h.isEmpty() && grants.forAccount(principal.accountId()).stream()
                .anyMatch(g -> g.host().equals(h));
        if (!ok) {
            throw PlatformAccessException.forbidden("Ce serveur ne vous est pas assigné.");
        }
        return principal;
    }

    /**
     * Route liée à un serveur, mais sans serveur dans la requête : refusée sauf admin plateforme (fail-closed).
     */
    public PlatformPrincipal requireServerOrAdmin(Permission permission) throws IOException {
        PlatformPrincipal principal = require(permission);
        if (!platformMode || principal == null || principal.isPlatformAdmin()) {
            return principal;
        }
        throw PlatformAccessException.forbidden("Serveur requis (host, port, user) pour cette action.");
    }

    public boolean currentIsPlatformAdmin() {
        return !platformMode || current().map(PlatformPrincipal::isPlatformAdmin).orElse(false);
    }

    public List<PlatformModels.ServerGrant> visibleGrants() throws IOException {
        PlatformPrincipal principal = requireLogin();
        if (!platformMode || principal == null) {
            return List.of();
        }
        if (principal.isPlatformAdmin()) {
            return grants.list();
        }
        return grants.forAccount(principal.accountId());
    }

    public PlatformPrincipal loadPrincipal(PlatformModels.Account account) throws IOException {
        PlatformModels.Role role = roles.require(account.roleId());
        return new PlatformPrincipal(
                account.id(),
                account.email(),
                account.displayName(),
                account.roleId(),
                new LinkedHashSet<>(role.permissions()),
                account.mustChangePassword());
    }

    public static void bind(PlatformPrincipal principal, String sessionId) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.setAttribute(ATTR_PRINCIPAL, principal, RequestAttributes.SCOPE_REQUEST);
            if (sessionId != null) {
                attrs.setAttribute(ATTR_SESSION_ID, sessionId, RequestAttributes.SCOPE_REQUEST);
            }
        }
    }

    /**
     * À appeler depuis un filtre HTTP : pose aussi les attributs sur la requête, car
     * {@link RequestContextHolder} n’est pas encore initialisé si le filtre est trop tôt.
     */
    public static void bind(jakarta.servlet.http.HttpServletRequest request, PlatformPrincipal principal,
                            String sessionId) {
        if (request != null) {
            request.setAttribute(ATTR_PRINCIPAL, principal);
            if (sessionId != null) {
                request.setAttribute(ATTR_SESSION_ID, sessionId);
            }
        }
        bind(principal, sessionId);
    }
}
