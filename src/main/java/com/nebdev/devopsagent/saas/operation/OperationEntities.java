package com.nebdev.devopsagent.saas.operation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OperationEntities {

    private OperationEntities() {}

    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "REJECTED", "CANCELLED", "EXPIRED");

    @Entity(name = "Operation")
    @Table(name = "operation")
    public static class Operation {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(name = "server_id", nullable = false)
        private UUID serverId;
        @Column(nullable = false)
        private String type;
        @Column(name = "risk_level", nullable = false)
        private String riskLevel;
        @Column(nullable = false)
        private String status;
        @Column(nullable = false)
        private String origin;
        @Column(name = "initiator_id")
        private UUID initiatorId;
        @Column(nullable = false)
        private String reason;
        @JdbcTypeCode(SqlTypes.JSON)
        @Column(nullable = false)
        private Map<String, Object> parameters;
        @JdbcTypeCode(SqlTypes.JSON)
        private Map<String, Object> plan;
        @Column(name = "plan_hash")
        private String planHash;
        @Column(name = "idempotency_key")
        private String idempotencyKey;
        @Column(name = "expires_at")
        private Instant expiresAt;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;
        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;
        @Version
        private long version;

        protected Operation() {}

        Operation(UUID organizationId, UUID serverId, OperationType type, String origin, UUID initiatorId, String reason,
                  Map<String, Object> parameters, String idempotencyKey) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.serverId = serverId;
            this.type = type.name();
            this.riskLevel = type.risk().name();
            this.status = "DRAFT";
            this.origin = origin;
            this.initiatorId = initiatorId;
            this.reason = reason == null ? "" : reason.strip();
            this.parameters = parameters == null ? Map.of() : parameters;
            this.idempotencyKey = idempotencyKey;
            this.createdAt = Instant.now();
            this.updatedAt = createdAt;
        }

        void planned(Map<String, Object> plan, String hash, Instant expiresAt) {
            this.plan = plan;
            this.planHash = hash;
            this.expiresAt = expiresAt;
        }

        void status(String s) {
            this.status = s;
            this.updatedAt = Instant.now();
        }

        public boolean isTerminal() { return TERMINAL.contains(status); }
        public OperationType typeEnum() { return OperationType.valueOf(type); }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public UUID getServerId() { return serverId; }
        public String getType() { return type; }
        public String getRiskLevel() { return riskLevel; }
        public String getStatus() { return status; }
        public String getOrigin() { return origin; }
        public UUID getInitiatorId() { return initiatorId; }
        public String getReason() { return reason; }
        public Map<String, Object> getParameters() { return parameters; }
        public Map<String, Object> getPlan() { return plan; }
        public String getPlanHash() { return planHash; }
        public Instant getExpiresAt() { return expiresAt; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
    }

    @Entity(name = "Approval")
    @Table(name = "operation_approval")
    public static class Approval {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(name = "operation_id", nullable = false)
        private UUID operationId;
        @Column(name = "approver_id", nullable = false)
        private UUID approverId;
        @Column(name = "plan_hash", nullable = false)
        private String planHash;
        @Column(nullable = false)
        private String decision;
        @Column(nullable = false)
        private String comment;
        @Column(name = "decided_at", nullable = false)
        private Instant decidedAt;
        @Column(name = "expires_at", nullable = false)
        private Instant expiresAt;

        protected Approval() {}

        Approval(UUID organizationId, UUID operationId, UUID approverId, String planHash, String decision,
                 String comment, Instant expiresAt) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.operationId = operationId;
            this.approverId = approverId;
            this.planHash = planHash;
            this.decision = decision;
            this.comment = comment == null ? "" : comment.strip();
            this.decidedAt = Instant.now();
            this.expiresAt = expiresAt;
        }

        public boolean validAt(Instant now) { return "APPROVED".equals(decision) && expiresAt.isAfter(now); }

        public UUID getId() { return id; }
        public UUID getOperationId() { return operationId; }
        public UUID getApproverId() { return approverId; }
        public String getPlanHash() { return planHash; }
        public String getDecision() { return decision; }
        public String getComment() { return comment; }
        public Instant getDecidedAt() { return decidedAt; }
        public Instant getExpiresAt() { return expiresAt; }
    }

    @Entity(name = "Execution")
    @Table(name = "operation_execution")
    public static class Execution {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(name = "operation_id", nullable = false)
        private UUID operationId;
        @Column(name = "server_id", nullable = false)
        private UUID serverId;
        @Column(name = "approval_id")
        private UUID approvalId;
        @Column(name = "idempotency_key", nullable = false, unique = true)
        private String idempotencyKey;
        @Column(nullable = false)
        private String status;
        @Column(name = "runner_id")
        private String runnerId;
        @Column(nullable = false)
        private int attempt = 1;
        @Column(name = "queued_at", nullable = false)
        private Instant queuedAt;
        @Column(name = "started_at")
        private Instant startedAt;
        @Column(name = "heartbeat_at")
        private Instant heartbeatAt;
        @Column(name = "ended_at")
        private Instant endedAt;
        @Column(name = "exit_code")
        private Integer exitCode;
        @Column(name = "output_excerpt")
        private String outputExcerpt;
        @Column(name = "output_sha256")
        private String outputSha256;
        private String error;
        @JdbcTypeCode(SqlTypes.JSON)
        private Map<String, Object> verification;

        protected Execution() {}

        Execution(UUID organizationId, UUID operationId, UUID serverId, UUID approvalId, String idempotencyKey, int attempt) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.operationId = operationId;
            this.serverId = serverId;
            this.approvalId = approvalId;
            this.idempotencyKey = idempotencyKey;
            this.status = "QUEUED";
            this.attempt = attempt;
            this.queuedAt = Instant.now();
        }

        void start(String runnerId, Instant now) {
            this.status = "RUNNING";
            this.runnerId = runnerId;
            this.startedAt = now;
            this.heartbeatAt = now;
        }

        void finish(boolean ok, Integer exitCode, String excerpt, String sha, String error, Map<String, Object> verification) {
            this.status = ok ? "SUCCEEDED" : "FAILED";
            this.exitCode = exitCode;
            this.outputExcerpt = excerpt;
            this.outputSha256 = sha;
            this.error = error;
            this.verification = verification;
            this.endedAt = Instant.now();
        }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public UUID getOperationId() { return operationId; }
        public UUID getServerId() { return serverId; }
        public UUID getApprovalId() { return approvalId; }
        public String getStatus() { return status; }
        public String getRunnerId() { return runnerId; }
        public int getAttempt() { return attempt; }
        public Instant getQueuedAt() { return queuedAt; }
        public Instant getStartedAt() { return startedAt; }
        public Instant getHeartbeatAt() { return heartbeatAt; }
        public Instant getEndedAt() { return endedAt; }
        public Integer getExitCode() { return exitCode; }
        public String getOutputExcerpt() { return outputExcerpt; }
        public String getOutputSha256() { return outputSha256; }
        public String getError() { return error; }
        public Map<String, Object> getVerification() { return verification; }
    }
}
