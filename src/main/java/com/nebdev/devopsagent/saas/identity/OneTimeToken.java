package com.nebdev.devopsagent.saas.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Jeton à usage unique (réinitialisation, vérification d'e-mail, invitation) ; seul le hash est stocké. */
@Entity
@Table(name = "one_time_token")
public class OneTimeToken {

    public enum Purpose { PASSWORD_RESET, EMAIL_VERIFY, INVITATION }

    @Id
    private UUID id;
    @Column(nullable = false)
    private String purpose;
    @Column(name = "user_id")
    private UUID userId;
    private String email;
    @Column(name = "organization_id")
    private UUID organizationId;
    @Column(name = "role_code")
    private String roleCode;
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OneTimeToken() {}

    public OneTimeToken(Purpose purpose, UUID userId, String email, UUID organizationId, String roleCode,
                        String tokenHash, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.purpose = purpose.name();
        this.userId = userId;
        this.email = email;
        this.organizationId = organizationId;
        this.roleCode = roleCode;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public boolean usableAt(Instant now, Purpose expected) {
        return usedAt == null && expiresAt.isAfter(now) && expected.name().equals(purpose);
    }

    public void markUsed(Instant now) { this.usedAt = now; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getEmail() { return email; }
    public UUID getOrganizationId() { return organizationId; }
    public String getRoleCode() { return roleCode; }
    public Instant getExpiresAt() { return expiresAt; }
}
