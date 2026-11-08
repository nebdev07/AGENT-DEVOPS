package com.nebdev.devopsagent.saas.credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Entités du coffre (ADR-051). Aucune n'est exposée telle quelle par l'API. */
public final class CredentialEntities {

    private CredentialEntities() {}

    @Entity(name = "OrgDataKey")
    @Table(name = "org_data_key")
    public static class OrgDataKey {
        @Id
        @Column(name = "organization_id")
        private UUID organizationId;
        @Column(name = "kek_id", nullable = false)
        private String kekId;
        @Column(name = "wrapped_dek", nullable = false)
        private byte[] wrappedDek;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;

        protected OrgDataKey() {}

        OrgDataKey(UUID organizationId, String kekId, byte[] wrappedDek) {
            this.organizationId = organizationId;
            this.kekId = kekId;
            this.wrappedDek = wrappedDek;
            this.createdAt = Instant.now();
        }

        public UUID getOrganizationId() { return organizationId; }
        public String getKekId() { return kekId; }
        public byte[] getWrappedDek() { return wrappedDek; }
    }

    @Entity(name = "ServerCredential")
    @Table(name = "server_credential")
    public static class ServerCredential {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(name = "server_id", nullable = false)
        private UUID serverId;
        @Column(nullable = false)
        private String kind = "SSH_KEY";
        @Column(name = "public_key", nullable = false)
        private String publicKey;
        @Column(name = "private_key_enc", nullable = false)
        private byte[] privateKeyEnc;
        @Column(nullable = false)
        private String fingerprint;
        @Column(nullable = false)
        private String status;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;
        @Column(name = "activated_at")
        private Instant activatedAt;
        @Column(name = "revoked_at")
        private Instant revokedAt;

        protected ServerCredential() {}

        ServerCredential(UUID id, UUID organizationId, UUID serverId, String publicKey, byte[] privateKeyEnc,
                         String fingerprint, String status) {
            this.id = id;
            this.organizationId = organizationId;
            this.serverId = serverId;
            this.publicKey = publicKey;
            this.privateKeyEnc = privateKeyEnc;
            this.fingerprint = fingerprint;
            this.status = status;
            this.createdAt = Instant.now();
            if ("ACTIVE".equals(status)) {
                this.activatedAt = createdAt;
            }
        }

        void activate() { this.status = "ACTIVE"; this.activatedAt = Instant.now(); }
        void revoke() { this.status = "REVOKED"; this.revokedAt = Instant.now(); }
        void reseal(byte[] sealed) { this.privateKeyEnc = sealed; }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public UUID getServerId() { return serverId; }
        public String getPublicKey() { return publicKey; }
        byte[] getPrivateKeyEnc() { return privateKeyEnc; }
        public String getFingerprint() { return fingerprint; }
        public String getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getActivatedAt() { return activatedAt; }
        public Instant getRevokedAt() { return revokedAt; }
    }

    @Entity(name = "OrgSecret")
    @Table(name = "org_secret")
    public static class OrgSecret {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(nullable = false)
        private String name;
        @Column(name = "value_enc", nullable = false)
        private byte[] valueEnc;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;
        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;

        protected OrgSecret() {}

        OrgSecret(UUID id, UUID organizationId, String name, byte[] valueEnc) {
            this.id = id;
            this.organizationId = organizationId;
            this.name = name;
            this.valueEnc = valueEnc;
            this.createdAt = Instant.now();
            this.updatedAt = createdAt;
        }

        public UUID getId() { return id; }
        public String getName() { return name; }
        byte[] getValueEnc() { return valueEnc; }
        public UUID getOrganizationId() { return organizationId; }
        void reseal(byte[] sealed) { this.valueEnc = sealed; this.updatedAt = Instant.now(); }
    }
}
