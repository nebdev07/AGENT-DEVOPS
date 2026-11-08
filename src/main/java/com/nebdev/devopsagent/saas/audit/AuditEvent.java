package com.nebdev.devopsagent.saas.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Événement d'audit : ajout seul (trigger + REVOKE en base), chaîné par hash au sein de son organisation. */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    private UUID id;
    @Column(name = "organization_id")
    private UUID organizationId;
    @Column(nullable = false)
    private long seq;
    @Column(name = "actor_type", nullable = false)
    private String actorType;
    @Column(name = "actor_id")
    private String actorId;
    @Column(nullable = false)
    private String action;
    @Column(name = "target_type")
    private String targetType;
    @Column(name = "target_id")
    private String targetId;
    @Column(nullable = false)
    private String outcome;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> details;
    private String ip;
    @Column(name = "correlation_id")
    private String correlationId;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "prev_hash", nullable = false)
    private String prevHash;
    @Column(nullable = false)
    private String hash;

    protected AuditEvent() {}

    AuditEvent(UUID id, UUID organizationId, long seq, String actorType, String actorId, String action,
               String targetType, String targetId, String outcome, Map<String, Object> details, String ip,
               String correlationId, Instant occurredAt, String prevHash, String hash) {
        this.id = id;
        this.organizationId = organizationId;
        this.seq = seq;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.outcome = outcome;
        this.details = details;
        this.ip = ip;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public long getSeq() { return seq; }
    public String getActorType() { return actorType; }
    public String getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getOutcome() { return outcome; }
    public Map<String, Object> getDetails() { return details; }
    public String getIp() { return ip; }
    public String getCorrelationId() { return correlationId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getPrevHash() { return prevHash; }
    public String getHash() { return hash; }
}
