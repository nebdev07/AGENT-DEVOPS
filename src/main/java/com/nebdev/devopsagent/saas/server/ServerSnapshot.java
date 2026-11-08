package com.nebdev.devopsagent.saas.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Résultat d'une analyse (discovery READ) : contenu du snapshot moteur, sans aucun secret. */
@Entity
@Table(name = "server_snapshot")
public class ServerSnapshot {

    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "server_id", nullable = false)
    private UUID serverId;
    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;
    @Column(name = "audit_score")
    private Integer auditScore;
    @Column(name = "audit_grade")
    private String auditGrade;

    protected ServerSnapshot() {}

    public ServerSnapshot(UUID organizationId, UUID serverId, Instant takenAt, Map<String, Object> payload,
                          Integer auditScore, String auditGrade) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.serverId = serverId;
        this.takenAt = takenAt;
        this.payload = payload;
        this.auditScore = auditScore;
        this.auditGrade = auditGrade;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getServerId() { return serverId; }
    public Instant getTakenAt() { return takenAt; }
    public Map<String, Object> getPayload() { return payload; }
    public Integer getAuditScore() { return auditScore; }
    public String getAuditGrade() { return auditGrade; }
}
