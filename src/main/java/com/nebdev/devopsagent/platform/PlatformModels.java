package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/** Modèles persistés sous {@code state/platform/} (ADR-046). */
public final class PlatformModels {

    private PlatformModels() {}

    public record Role(String id, String label, List<String> permissions) {
        public Role {
            id = requireId(id, "rôle");
            label = label == null ? id : label.strip();
            permissions = List.copyOf(Permission.parseAll(permissions));
        }
    }

    /**
     * {@code passwordHash} : BCrypt, jamais exposé dans les API admin.
     * {@code mustChangePassword} : true après seed / reset → changement obligatoire.
     */
    public record Account(String id, String email, String displayName, String roleId, boolean active,
                          Instant createdAt, String passwordHash, boolean mustChangePassword) {
        public Account {
            id = requireId(id, "compte");
            email = normalizeEmail(email);
            displayName = displayName == null ? "" : displayName.strip();
            roleId = requireId(roleId, "rôle");
            createdAt = createdAt == null ? Instant.now() : createdAt;
            passwordHash = passwordHash == null ? "" : passwordHash;
        }

        @JsonIgnore
        public boolean hasPassword() {
            return passwordHash != null && !passwordHash.isBlank();
        }

        /** Vue API sans hash. */
        public Account publicView() {
            return new Account(id, email, displayName, roleId, active, createdAt, "", mustChangePassword);
        }
    }

    public record ServerGrant(String id, String accountId, String host, int port, String user, String label,
                              Instant createdAt) {
        public ServerGrant {
            id = requireId(id, "grant");
            accountId = requireId(accountId, "compte");
            host = normalizeHost(host);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port SSH invalide.");
            }
            user = requireId(user, "utilisateur SSH");
            label = label == null ? "" : label.strip();
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }
    }

    public record Session(String id, String accountId, Instant expiresAt, Instant createdAt) {
        public Session {
            id = requireId(id, "session");
            accountId = requireId(accountId, "compte");
            if (expiresAt == null) {
                throw new IllegalArgumentException("Expiration de session requise.");
            }
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }

        public boolean validAt(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    public record MagicToken(String id, String accountId, String email, Instant expiresAt, boolean used) {
        public MagicToken {
            id = requireId(id, "jeton");
            accountId = requireId(accountId, "compte");
            email = normalizeEmail(email);
            if (expiresAt == null) {
                throw new IllegalArgumentException("Expiration du jeton requise.");
            }
        }
    }

    static String requireId(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Identifiant " + what + " requis.");
        }
        return raw.strip();
    }

    static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank() || !raw.contains("@")) {
            throw new IllegalArgumentException("Adresse e-mail invalide.");
        }
        return raw.strip().toLowerCase();
    }

    static String normalizeHost(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Hôte requis.");
        }
        return raw.strip().toLowerCase();
    }
}
