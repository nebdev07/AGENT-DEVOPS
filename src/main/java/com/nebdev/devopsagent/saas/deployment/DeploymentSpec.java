package com.nebdev.devopsagent.saas.deployment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Fiche de déploiement (app 1:1 ou plateforme N services) rattachée à un serveur d'organisation. */
@Entity
@Table(name = "deployment_spec")
public class DeploymentSpec {

    public static final String KIND_APP = "APP";
    public static final String KIND_PLATFORM = "PLATFORM";

    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "server_id", nullable = false)
    private UUID serverId;
    @Column(nullable = false)
    private String kind;
    @Column(nullable = false)
    private String slug;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> spec = new LinkedHashMap<>();
    @Column(name = "spec_version", nullable = false)
    private int specVersion = 1;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected DeploymentSpec() {}

    public DeploymentSpec(UUID organizationId, UUID serverId, String kind, String slug, Map<String, Object> spec) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.serverId = serverId;
        this.kind = kind;
        this.slug = slug;
        this.spec = new LinkedHashMap<>(spec);
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void replaceSpec(Map<String, Object> next) {
        this.spec = new LinkedHashMap<>(next);
        this.specVersion++;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getServerId() { return serverId; }
    public String getKind() { return kind; }
    public String getSlug() { return slug; }
    public Map<String, Object> getSpec() { return spec; }
    public int getSpecVersion() { return specVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
