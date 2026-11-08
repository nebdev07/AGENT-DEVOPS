package com.nebdev.devopsagent.saas.apitoken;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Jeton d'API : organisation unique, scopes, expiration obligatoire, révocable. Seul le hash est stocké. */
@Entity
@Table(name = "api_token")
public class ApiToken {

    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(nullable = false)
    private String name;
    @Column(name = "token_prefix", nullable = false)
    private String tokenPrefix;
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] scopes;
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiToken() {}

    ApiToken(UUID organizationId, String name, String tokenPrefix, String tokenHash, Set<String> scopes,
             UUID createdBy, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.name = name;
        this.tokenPrefix = tokenPrefix;
        this.tokenHash = tokenHash;
        this.scopes = scopes.toArray(String[]::new);
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public boolean isValid(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }

    void revoke(Instant now) { this.revokedAt = now; }

    void used(Instant now) { this.lastUsedAt = now; }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getTokenPrefix() { return tokenPrefix; }
    public Set<String> getScopes() { return new LinkedHashSet<>(Arrays.asList(scopes)); }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
}
