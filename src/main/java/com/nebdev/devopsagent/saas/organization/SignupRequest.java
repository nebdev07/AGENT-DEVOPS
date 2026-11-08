package com.nebdev.devopsagent.saas.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signup_request")
public class SignupRequest {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName = "";

    @Column(name = "organization_name", nullable = false)
    private String organizationName;

    @Column(nullable = false)
    private String locale = "en";

    @Column(name = "time_zone", nullable = false)
    private String timeZone = "UTC";

    @Column(nullable = false)
    private String currency = "USD";

    private String message;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "created_user_id")
    private UUID createdUserId;

    @Column(name = "created_org_id")
    private UUID createdOrgId;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SignupRequest() {}

    public SignupRequest(String email, String displayName, String organizationName, String locale, String timeZone,
                         String currency, String message, String ipAddress, String userAgent) {
        this.email = email;
        this.displayName = displayName == null ? "" : displayName.strip();
        this.organizationName = organizationName;
        this.locale = locale;
        this.timeZone = timeZone;
        this.currency = currency;
        this.message = message;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getOrganizationName() { return organizationName; }
    public String getLocale() { return locale; }
    public String getTimeZone() { return timeZone; }
    public String getCurrency() { return currency; }
    public String getMessage() { return message; }
    public String getStatus() { return status; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public UUID getCreatedUserId() { return createdUserId; }
    public UUID getCreatedOrgId() { return createdOrgId; }
    public Instant getCreatedAt() { return createdAt; }

    public void markApproved(UUID adminId, UUID userId, UUID orgId) {
        this.status = "APPROVED";
        this.decidedAt = Instant.now();
        this.decidedBy = adminId;
        this.createdUserId = userId;
        this.createdOrgId = orgId;
        this.updatedAt = Instant.now();
    }

    public void markRejected(UUID adminId) {
        this.status = "REJECTED";
        this.decidedAt = Instant.now();
        this.decidedBy = adminId;
        this.updatedAt = Instant.now();
    }
}
