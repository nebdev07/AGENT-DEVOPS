package com.nebdev.devopsagent.saas.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Personnel INOVNE (ADR-052). Ne donne aucun accès aux données d'un client sans accès support accordé. */
@Entity
@Table(name = "platform_staff")
public class PlatformStaff {

    @Id
    @Column(name = "user_id")
    private UUID userId;
    @Column(nullable = false)
    private String role;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlatformStaff() {}

    public PlatformStaff(UUID userId, String role) {
        requireKnown(role);
        this.userId = userId;
        this.role = role;
        this.createdAt = Instant.now();
    }

    static void requireKnown(String role) {
        if (!"PLATFORM_ADMIN".equals(role) && !"PLATFORM_OPERATOR".equals(role)) {
            throw new IllegalArgumentException("Rôle plateforme inconnu : " + role);
        }
    }

    public UUID getUserId() { return userId; }
    public String getRole() { return role; }
    public void setRole(String role) { requireKnown(role); this.role = role; }
    public Instant getCreatedAt() { return createdAt; }
}
