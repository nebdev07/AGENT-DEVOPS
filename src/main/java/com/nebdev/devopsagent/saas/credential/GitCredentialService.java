package com.nebdev.devopsagent.saas.credential;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.CryptoService;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import com.nebdev.devopsagent.saas.shared.SealedBox;
import com.nebdev.devopsagent.stack.AppSpec;
import com.nebdev.devopsagent.stack.GitDeployKeyStore;
import com.nebdev.devopsagent.stack.GitUrls;
import com.nebdev.devopsagent.stack.PlatformServiceSpec;
import com.nebdev.devopsagent.stack.PlatformSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Deploy keys Git (lecture seule) par organisation. Distinctes des clés SSH des serveurs.
 * En mode remote, les privées sont scellées pour le runner (comme {@link CredentialService}).
 */
@Service
public class GitCredentialService {

    public record Unlocked(UUID id, String keyId, String privatePem, String publicKey) {
        @Override
        public String toString() {
            return "UnlockedGit[" + keyId + "]";
        }
    }

    public record Sealed(UUID id, String keyId, String sealedBase64, String publicKey) {
        @Override
        public String toString() {
            return "SealedGit[" + keyId + "]";
        }
    }

    private final GitCredentialRepository credentials;
    private final CredentialService.DataKeys dataKeys;
    private final CryptoService crypto;
    private final OrgAccess access;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final PublicKey runnerKey;

    public GitCredentialService(GitCredentialRepository credentials, CredentialService.DataKeys dataKeys,
                                CryptoService crypto, OrgAccess access, AuditService audit, ObjectMapper mapper,
                                SaasProperties properties) {
        this.credentials = credentials;
        this.dataKeys = dataKeys;
        this.crypto = crypto;
        this.access = access;
        this.audit = audit;
        this.mapper = mapper;
        String pem = properties.getRunner().getPublicKey();
        this.runnerKey = !properties.getRunner().isEmbedded() && pem != null && !pem.isBlank()
                ? SealedBox.parsePublicKey(pem) : null;
    }

    @Transactional(readOnly = true)
    public List<GitCredential> list(UUID orgId) {
        access.require(orgId, OrgPermissions.CREDENTIAL_MANAGE);
        return credentials.findByOrganizationIdOrderByKeyIdAsc(orgId);
    }

    @Transactional
    public GitCredential generate(UUID orgId, String gitUrl) {
        access.require(orgId, OrgPermissions.CREDENTIAL_MANAGE);
        String normalized = requireSshUrl(gitUrl);
        String keyId = GitDeployKeyStore.keyId(normalized);
        SshKeys.Pair pair = SshKeys.generate("autopilot-git@" + keyId);
        return store(orgId, keyId, normalized, pair, "generated");
    }

    @Transactional
    public GitCredential importKey(UUID orgId, String gitUrl, String privatePem) {
        access.require(orgId, OrgPermissions.CREDENTIAL_MANAGE);
        String normalized = requireSshUrl(gitUrl);
        String keyId = GitDeployKeyStore.keyId(normalized);
        SshKeys.Pair pair = SshKeys.parse(privatePem, "autopilot-git@" + keyId);
        return store(orgId, keyId, normalized, pair, "imported");
    }

    @Transactional
    public void delete(UUID orgId, UUID id) {
        access.require(orgId, OrgPermissions.CREDENTIAL_MANAGE);
        GitCredential c = credentials.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> ApiException.notFound("git_credential"));
        credentials.delete(c);
        audit.record(orgId, AuditService.Entry.success("git_credential.deleted", "git_credential", id,
                Map.of("keyId", c.getKeyId())));
    }

    /** Présence d'une clé utilisable pour ce dépôt SSH (sans déchiffrement). */
    @Transactional(readOnly = true)
    public boolean present(UUID orgId, String gitUrl) {
        String keyId = GitDeployKeyStore.keyId(gitUrl);
        return !keyId.isEmpty() && credentials.findByOrganizationIdAndKeyId(orgId, keyId).isPresent();
    }

    /** Déchiffre les clés nécessaires à une spec de déploiement figée (embedded). */
    @Transactional(readOnly = true)
    public Map<String, String> unlockForDeploy(UUID orgId, Map<String, Object> parameters) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String url : sshUrls(parameters)) {
            String keyId = GitDeployKeyStore.keyId(url);
            GitCredential c = credentials.findByOrganizationIdAndKeyId(orgId, keyId)
                    .orElseThrow(() -> ApiException.unprocessable("git_credential_missing", keyId));
            if (SealedBox.isSealed(c.getPrivateKeyEnc())) {
                throw new IllegalStateException("sealed_for_remote_runner");
            }
            byte[] pem = crypto.decrypt(dek(orgId), orgId.toString(), "git|" + c.getId(), c.getPrivateKeyEnc());
            out.put(keyId, Base64.getEncoder().encodeToString(pem));
        }
        return Map.copyOf(out);
    }

    /** Scelle les clés Git pour le runner distant. */
    @Transactional(readOnly = true)
    public Map<String, Sealed> sealForDeploy(UUID orgId, Map<String, Object> parameters) {
        if (runnerKey == null) {
            throw new IllegalStateException("runner_public_key_missing");
        }
        Map<String, Sealed> out = new LinkedHashMap<>();
        for (String url : sshUrls(parameters)) {
            String keyId = GitDeployKeyStore.keyId(url);
            GitCredential c = credentials.findByOrganizationIdAndKeyId(orgId, keyId)
                    .orElseThrow(() -> ApiException.unprocessable("git_credential_missing", keyId));
            String sealed;
            if (SealedBox.isSealed(c.getPrivateKeyEnc())) {
                sealed = Base64.getEncoder().encodeToString(c.getPrivateKeyEnc());
            } else {
                byte[] plain = crypto.decrypt(dek(orgId), orgId.toString(), "git|" + c.getId(), c.getPrivateKeyEnc());
                sealed = Base64.getEncoder().encodeToString(SealedBox.seal(runnerKey, SealedBox.gitAad(orgId, c.getId()), plain));
            }
            out.put(keyId, new Sealed(c.getId(), keyId, sealed, c.getPublicKey()));
        }
        return Map.copyOf(out);
    }

    /** Refuse les URL SSH sans Deploy key enregistrée ; HTTPS toujours OK. */
    public void assertDeployGitAccess(UUID orgId, Map<String, Object> frozenSpecParams) {
        for (String url : sshUrls(frozenSpecParams)) {
            if (!present(orgId, url)) {
                throw ApiException.unprocessable("git_credential_missing", GitDeployKeyStore.keyId(url));
            }
        }
    }

    public static Map<String, Object> view(GitCredential c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("keyId", c.getKeyId());
        m.put("gitUrl", c.getGitUrl());
        m.put("publicKey", c.getPublicKey());
        m.put("fingerprint", c.getFingerprint());
        m.put("deployKeyPage", GitDeployKeyStore.deployKeyPage(c.getGitUrl()));
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    private GitCredential store(UUID orgId, String keyId, String gitUrl, SshKeys.Pair pair, String how) {
        UUID id = credentials.findByOrganizationIdAndKeyId(orgId, keyId).map(GitCredential::getId).orElseGet(UUID::randomUUID);
        byte[] enc = protect(orgId, SealedBox.gitAad(orgId, id), "git|" + id,
                pair.privatePem().getBytes(StandardCharsets.UTF_8));
        Optional<GitCredential> existing = credentials.findByOrganizationIdAndKeyId(orgId, keyId);
        GitCredential saved;
        if (existing.isPresent()) {
            GitCredential c = existing.get();
            c.replace(pair.publicOpenSsh(), enc, pair.fingerprint());
            saved = credentials.save(c);
        } else {
            saved = credentials.save(new GitCredential(id, orgId, keyId, gitUrl, pair.publicOpenSsh(), enc, pair.fingerprint()));
        }
        audit.record(orgId, AuditService.Entry.success("git_credential." + how, "git_credential", saved.getId(),
                Map.of("keyId", keyId)));
        return saved;
    }

    private byte[] protect(UUID orgId, String sealedAad, String purposeAad, byte[] plaintext) {
        if (runnerKey != null) {
            return SealedBox.seal(runnerKey, sealedAad, plaintext);
        }
        return crypto.encrypt(dek(orgId), orgId.toString(), purposeAad, plaintext);
    }

    private byte[] dek(UUID orgId) {
        return dataKeys.findById(orgId)
                .orElseGet(() -> dataKeys.saveAndFlush(new CredentialEntities.OrgDataKey(orgId, crypto.kekId(),
                        crypto.newWrappedDek(orgId.toString()))))
                .getWrappedDek();
    }

    private List<String> sshUrls(Map<String, Object> parameters) {
        if (parameters == null || !(parameters.get("spec") instanceof Map<?, ?>)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) parameters.get("spec");
        if (spec.containsKey("services")) {
            PlatformSpec platform = mapper.convertValue(spec, PlatformSpec.class);
            return platform.services().stream().map(PlatformServiceSpec::gitUrl).filter(GitUrls::ssh).toList();
        }
        AppSpec app = mapper.convertValue(spec, AppSpec.class);
        return GitUrls.ssh(app.gitUrl()) ? List.of(app.gitUrl()) : List.of();
    }

    private static String requireSshUrl(String gitUrl) {
        String n = GitUrls.normalize(gitUrl);
        if (!GitUrls.ssh(n)) {
            throw ApiException.badRequest("ssh_git_url_required");
        }
        if (GitDeployKeyStore.keyId(n).isEmpty()) {
            throw ApiException.badRequest("invalid_git_url");
        }
        return n;
    }
}
