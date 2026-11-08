package com.nebdev.devopsagent.saas.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Serveur d'une organisation. Les identifiants SSH sont ailleurs ({@code server_credential}, chiffrés). */
@Entity
@Table(name = "server")
public class Server {

    static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.:\\-]{1,253}$");
    static final Pattern USER = Pattern.compile("^[a-z_][a-z0-9_.-]{0,31}$");
    public static final Set<String> PROTECTIONS = Set.of("NONE", "READ_ONLY", "BLOCKED");

    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String host;
    @Column(name = "ssh_port", nullable = false)
    private int sshPort = 22;
    @Column(name = "ssh_user", nullable = false)
    private String sshUser;
    @Column(name = "os_family")
    private String osFamily;
    @Column(name = "os_version")
    private String osVersion;
    @Column(name = "connection_state", nullable = false)
    private String connectionState = "UNKNOWN";
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;
    @Column(name = "engine_version")
    private String engineVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> capabilities = new LinkedHashMap<>();
    @Column(name = "maintenance_state", nullable = false)
    private String maintenanceState = "NONE";
    @Column(nullable = false)
    private String protection = "NONE";
    @Column(name = "host_fingerprint")
    private String hostFingerprint;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Server() {}

    public Server(UUID organizationId, String name, String host, int port, String user) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.name = requireName(name);
        this.host = requireHost(host);
        this.sshPort = requirePort(port);
        this.sshUser = requireUser(user);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    static String requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("Nom de serveur invalide.");
        }
        return name.strip();
    }

    static String requireHost(String host) {
        if (host == null || !HOST.matcher(host.strip()).matches()) {
            throw new IllegalArgumentException("Hôte invalide.");
        }
        return host.strip().toLowerCase(Locale.ROOT);
    }

    static int requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port SSH invalide.");
        }
        return port;
    }

    static String requireUser(String user) {
        if (user == null || !USER.matcher(user.strip()).matches()) {
            throw new IllegalArgumentException("Utilisateur SSH invalide.");
        }
        return user.strip();
    }

    public void rename(String name) { this.name = requireName(name); touch(); }

    public void setProtection(String p) {
        if (!PROTECTIONS.contains(p)) {
            throw new IllegalArgumentException("Protection inconnue : " + p);
        }
        this.protection = p;
        touch();
    }

    /** Changer d'hôte, de port ou d'utilisateur invalide l'empreinte connue (nouvelle confiance à établir). */
    public void moveTo(String host, Integer port, String user) {
        String h = host == null ? this.host : requireHost(host);
        int p = port == null ? this.sshPort : requirePort(port);
        String u = user == null ? this.sshUser : requireUser(user);
        if (!h.equals(this.host) || p != this.sshPort) {
            this.hostFingerprint = null;
        }
        this.host = h;
        this.sshPort = p;
        this.sshUser = u;
        touch();
    }

    public void trustFingerprint(String fp) { this.hostFingerprint = fp; touch(); }

    public void observed(String state, String osFamily, String osVersion, Map<String, Object> caps, String engineVersion,
                         Instant at) {
        this.connectionState = state;
        if (osFamily != null) this.osFamily = osFamily;
        if (osVersion != null) this.osVersion = osVersion;
        if (caps != null) this.capabilities = new LinkedHashMap<>(caps);
        this.engineVersion = engineVersion;
        this.lastCheckedAt = at;
        touch();
    }

    public void setMaintenanceState(String state) { this.maintenanceState = state; touch(); }

    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getHost() { return host; }
    public int getSshPort() { return sshPort; }
    public String getSshUser() { return sshUser; }
    public String getOsFamily() { return osFamily; }
    public String getOsVersion() { return osVersion; }
    public String getConnectionState() { return connectionState; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public String getEngineVersion() { return engineVersion; }
    public Map<String, Object> getCapabilities() { return capabilities; }
    public String getMaintenanceState() { return maintenanceState; }
    public String getProtection() { return protection; }
    public String getHostFingerprint() { return hostFingerprint; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
