package com.nebdev.devopsagent.saas.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "membership")
public class Membership {

    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "role_code", nullable = false)
    private String roleCode;
    @Column(nullable = false)
    private String status = "ACTIVE";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Membership() {}

    public Membership(UUID organizationId, UUID userId, String roleCode) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.userId = userId;
        this.roleCode = roleCode;
        this.createdAt = Instant.now();
    }

    public boolean isActive() { return "ACTIVE".equals(status); }

    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }

    public void setStatus(String status) { this.status = status; }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    public String getRoleCode() { return roleCode; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
