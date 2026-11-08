package com.nebdev.devopsagent.saas.staging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity(name = "OperationStaging")
@Table(name = "operation_staging")
public class OperationStaging {

    public static final String KIND_SQL_DUMP = "SQL_DUMP";
    public static final String KIND_UPLOADS = "UPLOADS_ARCHIVE";
    public static final String KIND_SECRET = "PLATFORM_SECRET";

    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "server_id", nullable = false)
    private UUID serverId;
    @Column(nullable = false)
    private String kind;
    @Column(name = "original_name")
    private String originalName;
    @Column(name = "content_sha256", nullable = false)
    private String contentSha256;
    @Column(name = "content_enc", nullable = false)
    private byte[] contentEnc;
    @Column(name = "byte_size", nullable = false)
    private int byteSize;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> meta;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected OperationStaging() {}

    OperationStaging(UUID id, UUID organizationId, UUID serverId, String kind, String originalName,
                     String contentSha256, byte[] contentEnc, int byteSize, Map<String, Object> meta,
                     UUID createdBy, Instant expiresAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.serverId = serverId;
        this.kind = kind;
        this.originalName = originalName;
        this.contentSha256 = contentSha256;
        this.contentEnc = contentEnc;
        this.byteSize = byteSize;
        this.meta = meta == null ? Map.of() : Map.copyOf(meta);
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getServerId() { return serverId; }
    public String getKind() { return kind; }
    public String getOriginalName() { return originalName; }
    public String getContentSha256() { return contentSha256; }
    byte[] getContentEnc() { return contentEnc; }
    public int getByteSize() { return byteSize; }
    public Map<String, Object> getMeta() { return meta; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getCreatedAt() { return createdAt; }

    boolean usable(Instant now) {
        return consumedAt == null && expiresAt != null && now.isBefore(expiresAt);
    }

    void consume(Instant at) {
        this.consumedAt = at;
    }
}
