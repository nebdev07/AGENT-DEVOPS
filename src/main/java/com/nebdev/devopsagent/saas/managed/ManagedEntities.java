package com.nebdev.devopsagent.saas.managed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ManagedEntities {

    private ManagedEntities() {}

    @Entity(name = "Contract")
    @Table(name = "managed_contract")
    public static class Contract {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(name = "service_level", nullable = false)
        private String serviceLevel;
        @Column(nullable = false)
        private String status;
        @Column(name = "starts_on", nullable = false)
        private LocalDate startsOn;
        @Column(name = "ends_on")
        private LocalDate endsOn;
        @Column(name = "response_time_minutes", nullable = false)
        private int responseTimeMinutes;
        @Column(nullable = false)
        private String scope;
        @Column(name = "created_by")
        private UUID createdBy;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;

        protected Contract() {}

        Contract(UUID organizationId, String serviceLevel, LocalDate startsOn, LocalDate endsOn, int responseTime,
                 String scope, UUID createdBy) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.serviceLevel = serviceLevel;
            this.status = "ACTIVE";
            this.startsOn = startsOn;
            this.endsOn = endsOn;
            this.responseTimeMinutes = responseTime;
            this.scope = scope == null ? "" : scope;
            this.createdBy = createdBy;
            this.createdAt = Instant.now();
        }

        void setStatus(String s) { this.status = s; }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public String getServiceLevel() { return serviceLevel; }
        public String getStatus() { return status; }
        public LocalDate getStartsOn() { return startsOn; }
        public LocalDate getEndsOn() { return endsOn; }
        public int getResponseTimeMinutes() { return responseTimeMinutes; }
        public String getScope() { return scope; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity(name = "SupportGrant")
    @Table(name = "support_access_grant")
    public static class SupportGrant {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(name = "staff_user_id", nullable = false)
        private UUID staffUserId;
        @Column(name = "access_level", nullable = false)
        private String accessLevel;
        @Column(nullable = false)
        private String reason;
        @Column(name = "granted_by", nullable = false)
        private UUID grantedBy;
        @Column(name = "starts_at", nullable = false)
        private Instant startsAt;
        @Column(name = "expires_at", nullable = false)
        private Instant expiresAt;
        @Column(name = "revoked_at")
        private Instant revokedAt;
        @Column(name = "revoked_by")
        private UUID revokedBy;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;

        protected SupportGrant() {}

        SupportGrant(UUID organizationId, UUID staffUserId, String level, String reason, UUID grantedBy, Instant expiresAt) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.staffUserId = staffUserId;
            this.accessLevel = level;
            this.reason = reason;
            this.grantedBy = grantedBy;
            this.startsAt = Instant.now();
            this.expiresAt = expiresAt;
            this.createdAt = startsAt;
        }

        public boolean activeAt(Instant now) {
            return revokedAt == null && !startsAt.isAfter(now) && expiresAt.isAfter(now);
        }

        void revoke(UUID by) { this.revokedAt = Instant.now(); this.revokedBy = by; }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public UUID getStaffUserId() { return staffUserId; }
        public String getAccessLevel() { return accessLevel; }
        public String getReason() { return reason; }
        public UUID getGrantedBy() { return grantedBy; }
        public Instant getStartsAt() { return startsAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public Instant getRevokedAt() { return revokedAt; }
    }

    @Entity(name = "Intervention")
    @Table(name = "intervention")
    public static class Intervention {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(name = "contract_id")
        private UUID contractId;
        @Column(name = "server_id")
        private UUID serverId;
        @Column(nullable = false)
        private String title;
        @Column(nullable = false)
        private String description;
        @Column(nullable = false)
        private String priority;
        @Column(nullable = false)
        private String status;
        @Column(name = "requested_by")
        private UUID requestedBy;
        @Column(name = "assigned_to")
        private UUID assignedTo;
        @Column(name = "planned_for")
        private Instant plannedFor;
        @Column(name = "closed_at")
        private Instant closedAt;
        @Column(nullable = false)
        private String report;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;
        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;
        @Version
        private long version;

        protected Intervention() {}

        Intervention(UUID organizationId, UUID contractId, UUID serverId, String title, String description,
                     String priority, UUID requestedBy) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.contractId = contractId;
            this.serverId = serverId;
            this.title = title;
            this.description = description == null ? "" : description;
            this.priority = priority;
            this.status = "OPEN";
            this.requestedBy = requestedBy;
            this.report = "";
            this.createdAt = Instant.now();
            this.updatedAt = createdAt;
        }

        void update(String status, UUID assignedTo, Instant plannedFor, String report) {
            if (status != null) {
                this.status = status;
                if ("DONE".equals(status) || "CANCELLED".equals(status)) {
                    this.closedAt = Instant.now();
                }
            }
            if (assignedTo != null) this.assignedTo = assignedTo;
            if (plannedFor != null) this.plannedFor = plannedFor;
            if (report != null) this.report = report;
            this.updatedAt = Instant.now();
        }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public UUID getContractId() { return contractId; }
        public UUID getServerId() { return serverId; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getPriority() { return priority; }
        public String getStatus() { return status; }
        public UUID getRequestedBy() { return requestedBy; }
        public UUID getAssignedTo() { return assignedTo; }
        public Instant getPlannedFor() { return plannedFor; }
        public Instant getClosedAt() { return closedAt; }
        public String getReport() { return report; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
    }
}
