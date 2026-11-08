package com.nebdev.devopsagent.saas.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Rôle d'organisation : système (organizationId nul, non modifiable) ou propre à une organisation. */
@Entity
@Table(name = "org_role")
public class OrgRole {

    @Id
    private UUID id;
    @Column(name = "organization_id")
    private UUID organizationId;
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String label;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] permissions;
    @Column(nullable = false)
    private boolean system;

    protected OrgRole() {}

    public OrgRole(UUID organizationId, String code, String label, Set<String> permissions) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.code = code;
        this.label = label;
        setPermissions(permissions);
        this.system = false;
    }

    public void setPermissions(Set<String> perms) {
        OrgPermissions.requireKnown(perms);
        this.permissions = perms.toArray(String[]::new);
    }

    public void setLabel(String label) { this.label = label; }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public Set<String> getPermissions() { return new LinkedHashSet<>(Arrays.asList(permissions)); }
    public boolean isSystem() { return system; }
}
