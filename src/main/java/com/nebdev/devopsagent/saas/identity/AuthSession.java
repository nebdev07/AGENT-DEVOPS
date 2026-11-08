package com.nebdev.devopsagent.saas.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Session : seul le SHA-256 du jeton est stocké. Le jeton lui-même n'est connu que du client. */
@Entity
@Table(name = "auth_session")
public class AuthSession {

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
    private String ip;
    @Column(name = "user_agent")
    private String userAgent;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AuthSession() {}

    public AuthSession(UUID userId, String tokenHash, Instant expiresAt, String ip, String userAgent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.ip = ip;
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length()));
    }

    public boolean isValid(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }

    public void revoke(Instant now) { this.revokedAt = now; }

    public void seen(Instant now) { this.lastSeenAt = now; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }
}
