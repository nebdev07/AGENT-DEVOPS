package com.nebdev.devopsagent.saas.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Clé Deploy Git (lecture seule) chiffrée, une par dépôt SSH d'une organisation. */
@Entity
@Table(name = "git_credential")
public class GitCredential {

    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "key_id", nullable = false)
    private String keyId;
    @Column(name = "git_url", nullable = false)
    private String gitUrl;
    @Column(name = "public_key", nullable = false)
    private String publicKey;
    @Column(name = "private_key_enc", nullable = false)
    private byte[] privateKeyEnc;
    @Column(nullable = false)
    private String fingerprint;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GitCredential() {}

    public GitCredential(UUID id, UUID organizationId, String keyId, String gitUrl, String publicKey,
                         byte[] privateKeyEnc, String fingerprint) {
        this.id = id;
        this.organizationId = organizationId;
        this.keyId = keyId;
        this.gitUrl = gitUrl;
        this.publicKey = publicKey;
        this.privateKeyEnc = privateKeyEnc;
        this.fingerprint = fingerprint;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void replace(String publicKey, byte[] privateKeyEnc, String fingerprint) {
        this.publicKey = publicKey;
        this.privateKeyEnc = privateKeyEnc;
        this.fingerprint = fingerprint;
        this.updatedAt = Instant.now();
    }

    public void reseal(byte[] privateKeyEnc) {
        this.privateKeyEnc = privateKeyEnc;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getKeyId() { return keyId; }
    public String getGitUrl() { return gitUrl; }
    public String getPublicKey() { return publicKey; }
    public byte[] getPrivateKeyEnc() { return privateKeyEnc; }
    public String getFingerprint() { return fingerprint; }
    public Instant getCreatedAt() { return createdAt; }
}
