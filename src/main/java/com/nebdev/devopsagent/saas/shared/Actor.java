package com.nebdev.devopsagent.saas.shared;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Qui agit : un utilisateur (session), un jeton d'API (limité à une organisation et à des scopes)
 * ou le runner d'exécution. Le rôle plateforme INOVNE ({@code staffRole}) ne donne <b>aucun</b> accès
 * implicite aux données des clients (ADR-052).
 */
public record Actor(Type type, UUID userId, String email, String staffRole, UUID tokenId, UUID tokenOrgId,
                    Set<String> scopes, String locale) {

    public enum Type { USER, API_TOKEN, RUNNER }

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String PLATFORM_OPERATOR = "PLATFORM_OPERATOR";

    public boolean isStaff() {
        return staffRole != null;
    }

    public boolean isPlatformAdmin() {
        return PLATFORM_ADMIN.equals(staffRole);
    }

    public String auditType() {
        return switch (type) {
            case USER -> isStaff() ? "STAFF" : "USER";
            case API_TOKEN -> "API_TOKEN";
            case RUNNER -> "RUNNER";
        };
    }

    public String auditId() {
        return type == Type.API_TOKEN ? String.valueOf(tokenId) : userId == null ? "runner" : userId.toString();
    }

    public static Optional<Actor> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Actor actor) {
            return Optional.of(actor);
        }
        return Optional.empty();
    }

    public static Actor require() {
        return current().orElseThrow(() -> ApiException.unauthorized("auth_required"));
    }
}
