package com.nebdev.devopsagent.saas.credential;

import com.nebdev.devopsagent.saas.shared.CryptoService;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import com.nebdev.devopsagent.saas.shared.SealedBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.server.Server;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.shared.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Coffre des identifiants (ADR-051). Une clé SSH par serveur, chiffrée par la clé de l'organisation.
 * Les clés privées ne sortent jamais par l'API ; seul le moteur les déchiffre, le temps d'une exécution.
 */
@Service
public class CredentialService {

    public interface DataKeys extends JpaRepository<CredentialEntities.OrgDataKey, UUID> {}

    public interface Credentials extends JpaRepository<CredentialEntities.ServerCredential, UUID> {
        Optional<CredentialEntities.ServerCredential> findByServerIdAndStatus(UUID serverId, String status);

        List<CredentialEntities.ServerCredential> findByServerIdOrderByCreatedAtDesc(UUID serverId);
    }

    public interface Secrets extends JpaRepository<CredentialEntities.OrgSecret, UUID> {
        Optional<CredentialEntities.OrgSecret> findByOrganizationIdAndName(UUID org, String name);
    }

    /** Clé déchiffrée, à n'utiliser que dans le moteur et à oublier aussitôt. */
    public record Unlocked(UUID credentialId, String privatePem, String publicKey) {
        @Override
        public String toString() {
            return "Unlocked[" + credentialId + ", private=***]";
        }
    }

    /** Valeur scellée pour le runner séparé (base64), avec ce qui permet au runner de recalculer l'AAD. */
    public record Sealed(UUID credentialId, String sealedBase64, String publicKey) {
        @Override
        public String toString() {
            return "Sealed[" + credentialId + "]";
        }
    }

    public static final String BACKUP_SECRET = "backup.passphrase";
    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    private final DataKeys dataKeys;
    private final Credentials credentials;
    private final Secrets secrets;
    private final CryptoService crypto;
    private final OrgAccess access;
    private final ServerService servers;
    private final AuditService audit;
    private final RlsContext rls;
    /** Mode remote (ADR-055) : clé publique du runner ; null en mode embedded. */
    private final PublicKey runnerKey;
    private final SecureRandom random = new SecureRandom();

    public CredentialService(DataKeys dataKeys, Credentials credentials, Secrets secrets, CryptoService crypto,
                             OrgAccess access, ServerService servers, AuditService audit, RlsContext rls,
                             SaasProperties properties) {
        this.dataKeys = dataKeys;
        this.credentials = credentials;
        this.secrets = secrets;
        this.crypto = crypto;
        this.access = access;
        this.servers = servers;
        this.audit = audit;
        this.rls = rls;
        String pem = properties.getRunner().getPublicKey();
        this.runnerKey = !properties.getRunner().isEmbedded() && pem != null && !pem.isBlank()
                ? SealedBox.parsePublicKey(pem) : null;
    }

    public boolean sealsForRunner() {
        return runnerKey != null;
    }

    /** Chiffre pour le stockage : scellé pour le runner (remote) ou enveloppe KEK/DEK (embedded). */
    private byte[] protect(UUID orgId, String sealedAad, String purposeAad, byte[] plaintext) {
        if (runnerKey != null) {
            return SealedBox.seal(runnerKey, sealedAad, plaintext);
        }
        return crypto.encrypt(dek(orgId), orgId.toString(), purposeAad, plaintext);
    }

    /** Déchiffrement local (embedded seulement) : une valeur scellée ne se rouvre jamais ici. */
    private byte[] reveal(UUID orgId, String purposeAad, byte[] stored) {
        if (SealedBox.isSealed(stored)) {
            throw new IllegalStateException("sealed_for_remote_runner");
        }
        return crypto.decrypt(dek(orgId), orgId.toString(), purposeAad, stored);
    }

    /** Génère une clé : ACTIVE si le serveur n'en a pas, sinon PENDING (à poser par une opération de rotation). */
    @Transactional
    public CredentialEntities.ServerCredential generate(UUID orgId, UUID serverId) {
        access.require(orgId, OrgPermissions.CREDENTIAL_MANAGE);
        Server server = servers.require(orgId, serverId);
        SshKeys.Pair pair = SshKeys.generate("vps-autopilot@" + server.getName().replaceAll("\\s+", "-"));
        return store(orgId, server, pair, "generated");
    }

    @Transactional
    public CredentialEntities.ServerCredential importKey(UUID orgId, UUID serverId, String privatePem) {
        access.require(orgId, OrgPermissions.CREDENTIAL_MANAGE);
        Server server = servers.require(orgId, serverId);
        SshKeys.Pair pair;
        try {
            pair = SshKeys.parse(privatePem, "vps-autopilot@" + server.getName().replaceAll("\\s+", "-"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_private_key", e.getMessage());
        }
        return store(orgId, server, pair, "imported");
    }

    /** Migration (ADR-057) : clé SSH existante de la plateforme reprise pour un serveur (sans contexte utilisateur). */
    @Transactional(propagation = Propagation.MANDATORY)
    public CredentialEntities.ServerCredential importKeyForMigration(UUID orgId, Server server, String privatePem) {
        if (credentials.findByServerIdAndStatus(server.getId(), "ACTIVE").isPresent()) {
            return credentials.findByServerIdAndStatus(server.getId(), "ACTIVE").get();
        }
        return store(orgId, server, SshKeys.parse(privatePem, "vps-autopilot@" + server.getName().replaceAll("\\s+", "-")),
                "imported");
    }

    private CredentialEntities.ServerCredential store(UUID orgId, Server server, SshKeys.Pair pair, String how) {
        boolean hasActive = credentials.findByServerIdAndStatus(server.getId(), "ACTIVE").isPresent();
        credentials.findByServerIdAndStatus(server.getId(), "PENDING").ifPresent(p -> {
            p.revoke();
            credentials.flush();
        });
        UUID id = UUID.randomUUID();
        byte[] enc = protect(orgId, SealedBox.credentialAad(orgId, id), "credential|" + id,
                pair.privatePem().getBytes(StandardCharsets.UTF_8));
        CredentialEntities.ServerCredential c = credentials.save(new CredentialEntities.ServerCredential(
                id, orgId, server.getId(), pair.publicOpenSsh(), enc, pair.fingerprint(), hasActive ? "PENDING" : "ACTIVE"));
        audit.record(orgId, AuditService.Entry.success("credential." + how, "server", server.getId(),
                Map.of("credentialId", id.toString(), "fingerprint", pair.fingerprint(), "status", c.getStatus())));
        return c;
    }

    @Transactional
    public void revokeActive(UUID orgId, UUID serverId) {
        access.require(orgId, OrgPermissions.CREDENTIAL_MANAGE);
        servers.require(orgId, serverId);
        CredentialEntities.ServerCredential c = credentials.findByServerIdAndStatus(serverId, "ACTIVE")
                .orElseThrow(() -> ApiException.notFound("credential"));
        c.revoke();
        audit.record(orgId, AuditService.Entry.success("credential.revoked", "server", serverId,
                Map.of("credentialId", c.getId().toString(), "fingerprint", c.getFingerprint())));
    }

    @Transactional(readOnly = true)
    public List<CredentialEntities.ServerCredential> list(UUID orgId, UUID serverId) {
        access.require(orgId, OrgPermissions.SERVER_READ);
        servers.require(orgId, serverId);
        return credentials.findByServerIdOrderByCreatedAtDesc(serverId);
    }

    /** Clé publique d'un identifiant (aucun déchiffrement) : existence et rotation, côté control plane. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> publicKeyOf(UUID orgId, UUID serverId, String status) {
        return credentials.findByServerIdAndStatus(serverId, status)
                .filter(c -> c.getOrganizationId().equals(orgId))
                .map(CredentialEntities.ServerCredential::getPublicKey);
    }

    // ───────────── Moteur seulement (contexte RLS de l'organisation déjà posé) ─────────────

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Unlocked> unlock(UUID orgId, UUID serverId, String status) {
        return credentials.findByServerIdAndStatus(serverId, status)
                .filter(c -> c.getOrganizationId().equals(orgId))
                .map(c -> new Unlocked(c.getId(), new String(reveal(orgId, "credential|" + c.getId(),
                        c.getPrivateKeyEnc()), StandardCharsets.UTF_8), c.getPublicKey()));
    }

    /**
     * Mode remote : la clé telle que stockée (scellée pour le runner), sans déchiffrement. Une clé encore au format
     * enveloppe (antérieure au passage en remote) est rescellée au passage.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Sealed> sealedFor(UUID orgId, UUID serverId, String status) {
        requireSealing();
        return credentials.findByServerIdAndStatus(serverId, status)
                .filter(c -> c.getOrganizationId().equals(orgId))
                .map(c -> {
                    resealIfNeeded(c);
                    return new Sealed(c.getId(), Base64.getEncoder().encodeToString(c.getPrivateKeyEnc()), c.getPublicKey());
                });
    }

    /** Mode remote : phrase de sauvegarde scellée (créée au premier usage, le control plane l'oublie aussitôt). */
    @Transactional(propagation = Propagation.MANDATORY)
    public String sealedBackupPassphrase(UUID orgId) {
        requireSealing();
        CredentialEntities.OrgSecret secret = secrets.findByOrganizationIdAndName(orgId, BACKUP_SECRET)
                .orElseGet(() -> secrets.save(new CredentialEntities.OrgSecret(UUID.randomUUID(), orgId, BACKUP_SECRET,
                        protect(orgId, SealedBox.secretAad(orgId, BACKUP_SECRET), "secret|" + BACKUP_SECRET,
                                newPassphrase().getBytes(StandardCharsets.UTF_8)))));
        resealIfNeeded(secret);
        return Base64.getEncoder().encodeToString(secret.getValueEnc());
    }

    /**
     * Passage en remote (ADR-055) : toutes les valeurs encore chiffrées par la KEK sont rescellées pour le runner.
     * Après cela, la clé maîtresse du control plane ne permet plus de lire aucune clé SSH.
     *
     * @return nombre de valeurs rescellées
     */
    @Transactional
    public int resealAll() {
        if (runnerKey == null) {
            return 0;
        }
        rls.enterPlatform();
        int n = 0;
        for (CredentialEntities.ServerCredential c : credentials.findAll()) {
            if (!"REVOKED".equals(c.getStatus()) && resealIfNeeded(c)) {
                n++;
            }
        }
        for (CredentialEntities.OrgSecret s : secrets.findAll()) {
            if (resealIfNeeded(s)) {
                n++;
            }
        }
        if (n > 0) {
            log.info("Coffre : {} valeur(s) rescellée(s) pour le runner séparé ({}).", n, SealedBox.keyId(runnerKey));
        }
        return n;
    }

    private boolean resealIfNeeded(CredentialEntities.ServerCredential c) {
        if (SealedBox.isSealed(c.getPrivateKeyEnc())) {
            return false;
        }
        UUID org = c.getOrganizationId();
        byte[] plain = crypto.decrypt(dek(org), org.toString(), "credential|" + c.getId(), c.getPrivateKeyEnc());
        try {
            c.reseal(SealedBox.seal(runnerKey, SealedBox.credentialAad(org, c.getId()), plain));
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
        return true;
    }

    private boolean resealIfNeeded(CredentialEntities.OrgSecret s) {
        if (SealedBox.isSealed(s.getValueEnc())) {
            return false;
        }
        UUID org = s.getOrganizationId();
        byte[] plain = crypto.decrypt(dek(org), org.toString(), "secret|" + s.getName(), s.getValueEnc());
        try {
            s.reseal(SealedBox.seal(runnerKey, SealedBox.secretAad(org, s.getName()), plain));
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
        return true;
    }

    private void requireSealing() {
        if (runnerKey == null) {
            throw new IllegalStateException("runner_public_key_missing");
        }
    }

    private String newPassphrase() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Rotation réussie : la clé PENDING devient ACTIVE, l'ancienne est révoquée. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void promotePending(UUID orgId, UUID serverId) {
        CredentialEntities.ServerCredential pending = credentials.findByServerIdAndStatus(serverId, "PENDING")
                .orElseThrow(() -> new IllegalStateException("Aucune clé en attente."));
        credentials.findByServerIdAndStatus(serverId, "ACTIVE").ifPresent(a -> {
            a.revoke();
            credentials.flush();
        });
        pending.activate();
        audit.record(orgId, AuditService.Entry.success("credential.rotated", "server", serverId,
                Map.of("credentialId", pending.getId().toString(), "fingerprint", pending.getFingerprint())));
    }

    /** Phrase de passe des sauvegardes de l'organisation (créée au premier usage, jamais exposée). */
    @Transactional(propagation = Propagation.MANDATORY)
    public String backupPassphrase(UUID orgId) {
        Optional<CredentialEntities.OrgSecret> existing = secrets.findByOrganizationIdAndName(orgId, BACKUP_SECRET);
        if (existing.isPresent()) {
            return new String(reveal(orgId, "secret|" + BACKUP_SECRET, existing.get().getValueEnc()), StandardCharsets.UTF_8);
        }
        String hex = newPassphrase();
        secrets.save(new CredentialEntities.OrgSecret(UUID.randomUUID(), orgId, BACKUP_SECRET,
                protect(orgId, SealedBox.secretAad(orgId, BACKUP_SECRET), "secret|" + BACKUP_SECRET,
                        hex.getBytes(StandardCharsets.UTF_8))));
        return hex;
    }

    private byte[] dek(UUID orgId) {
        return dataKeys.findById(orgId)
                .orElseGet(() -> dataKeys.saveAndFlush(new CredentialEntities.OrgDataKey(orgId, crypto.kekId(),
                        crypto.newWrappedDek(orgId.toString()))))
                .getWrappedDek();
    }

    public static Map<String, Object> view(CredentialEntities.ServerCredential c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("status", c.getStatus());
        m.put("publicKey", c.getPublicKey());
        m.put("fingerprint", c.getFingerprint());
        m.put("createdAt", c.getCreatedAt());
        m.put("activatedAt", c.getActivatedAt());
        m.put("revokedAt", c.getRevokedAt());
        return m;
    }
}
