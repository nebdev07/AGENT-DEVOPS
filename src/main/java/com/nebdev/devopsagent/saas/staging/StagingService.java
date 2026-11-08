package com.nebdev.devopsagent.saas.staging;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.credential.CredentialService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.CryptoService;
import com.nebdev.devopsagent.saas.shared.Hashing;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import com.nebdev.devopsagent.saas.shared.SealedBox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dépôt temporaire chiffré pour restore / secrets one-shot. La valeur n'est jamais exposée par l'API ;
 * seule une empreinte et des métadonnées figurent dans les paramètres d'opération.
 */
@Service
public class StagingService {

    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_SECRET_CHARS = 4096;
    private static final Duration TTL = Duration.ofHours(2);
    private static final Pattern SECRET_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]{0,127}$");

    public record Sealed(UUID id, String kind, String originalName, String sealedBase64, String contentSha256) {}

    public record Opened(UUID id, String kind, String originalName, String contentSha256, byte[] content,
                         Map<String, Object> meta) {
        @Override
        public String toString() {
            return "Opened[" + id + "," + kind + "]";
        }
    }

    private final OperationStagingRepository staging;
    private final CredentialService.DataKeys dataKeys;
    private final CryptoService crypto;
    private final OrgAccess access;
    private final ServerService servers;
    private final AuditService audit;
    private final PublicKey runnerKey;

    public StagingService(OperationStagingRepository staging, CredentialService.DataKeys dataKeys, CryptoService crypto,
                          OrgAccess access, ServerService servers, AuditService audit, SaasProperties properties) {
        this.staging = staging;
        this.dataKeys = dataKeys;
        this.crypto = crypto;
        this.access = access;
        this.servers = servers;
        this.audit = audit;
        String pem = properties.getRunner().getPublicKey();
        this.runnerKey = !properties.getRunner().isEmbedded() && pem != null && !pem.isBlank()
                ? SealedBox.parsePublicKey(pem) : null;
    }

    @Transactional
    public Map<String, Object> storeRestoreFile(UUID orgId, UUID serverId, String kind, MultipartFile file) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.OPERATION_PROPOSE);
        servers.require(orgId, serverId);
        if (!OperationStaging.KIND_SQL_DUMP.equals(kind) && !OperationStaging.KIND_UPLOADS.equals(kind)) {
            throw ApiException.badRequest("staging_kind_invalid");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("staging_file_required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("staging_too_large");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("staging_unreadable");
        }
        String name = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        validateRestoreName(kind, name);
        OperationStaging row = persist(orgId, serverId, kind, name, bytes, Map.of(), ctx.actor().userId());
        audit.record(orgId, AuditService.Entry.success("staging.stored", "staging", row.getId(),
                Map.of("kind", kind, "sha256", row.getContentSha256(), "bytes", row.getByteSize())));
        return view(row);
    }

    @Transactional
    public Map<String, Object> storeSecret(UUID orgId, UUID serverId, String serviceId, String name, String value,
                                           boolean restart) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.OPERATION_PROPOSE);
        servers.require(orgId, serverId);
        if (serviceId == null || serviceId.isBlank()) {
            throw ApiException.badRequest("service_required");
        }
        requireSecretName(name);
        requireSecretValue(value);
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw ApiException.badRequest("staging_too_large");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("serviceId", serviceId.trim().toLowerCase());
        meta.put("secretName", name.trim());
        meta.put("restart", restart);
        OperationStaging row = persist(orgId, serverId, OperationStaging.KIND_SECRET, name.trim(), bytes, meta,
                ctx.actor().userId());
        audit.record(orgId, AuditService.Entry.success("staging.secret_stored", "staging", row.getId(),
                Map.of("serviceId", meta.get("serviceId"), "secretName", name.trim(), "sha256", row.getContentSha256())));
        return view(row);
    }

    /**
     * Figé les références staging dans les paramètres d'opération (empreintes, métadonnées) sans la valeur.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> bindForOperation(UUID orgId, UUID serverId, Map<String, Object> params) {
        access.enter(orgId);
        Map<String, Object> next = new LinkedHashMap<>(params == null ? Map.of() : params);
        Instant now = Instant.now();
        bindOne(orgId, serverId, next, "sqlStagingId", OperationStaging.KIND_SQL_DUMP, "sqlSha256", "sqlOriginalName", now);
        bindOne(orgId, serverId, next, "uploadsStagingId", OperationStaging.KIND_UPLOADS, "uploadsSha256",
                "uploadsOriginalName", now);
        if (next.get("secretStagingId") != null) {
            OperationStaging row = requireUsable(orgId, serverId, uuid(next.get("secretStagingId")),
                    OperationStaging.KIND_SECRET, now);
            next.put("secretStagingId", row.getId().toString());
            next.put("secretSha256", row.getContentSha256());
            next.put("serviceId", String.valueOf(row.getMeta().getOrDefault("serviceId", "")));
            next.put("secretName", String.valueOf(row.getMeta().getOrDefault("secretName", "")));
            next.put("restart", Boolean.TRUE.equals(row.getMeta().get("restart")));
        }
        return Map.copyOf(next);
    }

    /** Déchiffre pour exécution embedded (refuse les blobs scellés runner). */
    @Transactional(readOnly = true)
    public Opened unlock(UUID orgId, UUID stagingId) {
        OperationStaging row = staging.findByOrganizationIdAndId(orgId, stagingId)
                .orElseThrow(() -> ApiException.notFound("staging"));
        if (!row.usable(Instant.now())) {
            throw ApiException.unprocessable("staging_unavailable");
        }
        if (SealedBox.isSealed(row.getContentEnc())) {
            throw new IllegalStateException("sealed_for_remote_runner");
        }
        byte[] plain = crypto.decrypt(dek(orgId), orgId.toString(), purpose(row.getId()), row.getContentEnc());
        return new Opened(row.getId(), row.getKind(), row.getOriginalName(), row.getContentSha256(), plain, row.getMeta());
    }

    /** Scelle pour le runner distant (ou renvoie le sceau déjà en base). */
    @Transactional(readOnly = true)
    public Sealed sealForRunner(UUID orgId, UUID stagingId) {
        if (runnerKey == null) {
            throw new IllegalStateException("runner_public_key_missing");
        }
        OperationStaging row = staging.findByOrganizationIdAndId(orgId, stagingId)
                .orElseThrow(() -> ApiException.notFound("staging"));
        if (!row.usable(Instant.now())) {
            throw ApiException.unprocessable("staging_unavailable");
        }
        String sealed;
        if (SealedBox.isSealed(row.getContentEnc())) {
            sealed = Base64.getEncoder().encodeToString(row.getContentEnc());
        } else {
            byte[] plain = crypto.decrypt(dek(orgId), orgId.toString(), purpose(row.getId()), row.getContentEnc());
            sealed = Base64.getEncoder().encodeToString(
                    SealedBox.seal(runnerKey, SealedBox.stagingAad(orgId, row.getId()), plain));
        }
        return new Sealed(row.getId(), row.getKind(), row.getOriginalName(), sealed, row.getContentSha256());
    }

    @Transactional
    public void markConsumed(UUID orgId, UUID stagingId) {
        staging.findByOrganizationIdAndId(orgId, stagingId).ifPresent(row -> {
            if (row.getConsumedAt() == null) {
                row.consume(Instant.now());
            }
        });
    }

    public static Map<String, Object> view(OperationStaging row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("kind", row.getKind());
        m.put("originalName", row.getOriginalName());
        m.put("contentSha256", row.getContentSha256());
        m.put("byteSize", row.getByteSize());
        m.put("meta", row.getMeta());
        m.put("expiresAt", row.getExpiresAt());
        m.put("createdAt", row.getCreatedAt());
        return m;
    }

    private OperationStaging persist(UUID orgId, UUID serverId, String kind, String originalName, byte[] bytes,
                                     Map<String, Object> meta, UUID createdBy) {
        UUID id = UUID.randomUUID();
        String sha = Hashing.sha256Hex(bytes);
        byte[] enc = protect(orgId, id, bytes);
        return staging.save(new OperationStaging(id, orgId, serverId, kind, originalName, sha, enc, bytes.length,
                meta, createdBy, Instant.now().plus(TTL)));
    }

    private byte[] protect(UUID orgId, UUID stagingId, byte[] plaintext) {
        if (runnerKey != null) {
            return SealedBox.seal(runnerKey, SealedBox.stagingAad(orgId, stagingId), plaintext);
        }
        return crypto.encrypt(dek(orgId), orgId.toString(), purpose(stagingId), plaintext);
    }

    private byte[] dek(UUID orgId) {
        return dataKeys.findById(orgId).orElseThrow(() -> ApiException.unprocessable("org_key_missing")).getWrappedDek();
    }

    private static String purpose(UUID stagingId) {
        return "staging|" + stagingId;
    }

    private void bindOne(UUID orgId, UUID serverId, Map<String, Object> next, String idKey, String expectedKind,
                         String shaKey, String nameKey, Instant now) {
        if (next.get(idKey) == null) {
            return;
        }
        OperationStaging row = requireUsable(orgId, serverId, uuid(next.get(idKey)), expectedKind, now);
        next.put(idKey, row.getId().toString());
        next.put(shaKey, row.getContentSha256());
        next.put(nameKey, row.getOriginalName());
    }

    private OperationStaging requireUsable(UUID orgId, UUID serverId, UUID id, String kind, Instant now) {
        OperationStaging row = staging.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> ApiException.badRequest("staging_not_found"));
        if (!row.getServerId().equals(serverId)) {
            throw ApiException.unprocessable("staging_server_mismatch");
        }
        if (!kind.equals(row.getKind())) {
            throw ApiException.unprocessable("staging_kind_mismatch");
        }
        if (!row.usable(now)) {
            throw ApiException.unprocessable("staging_unavailable");
        }
        return row;
    }

    private static UUID uuid(Object raw) {
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("staging_not_found");
        }
    }

    private static void validateRestoreName(String kind, String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        if (OperationStaging.KIND_SQL_DUMP.equals(kind)) {
            if (!(n.endsWith(".sql") || n.endsWith(".sql.gz"))) {
                throw ApiException.badRequest("staging_sql_extension");
            }
        } else if (OperationStaging.KIND_UPLOADS.equals(kind)) {
            if (!(n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".tar") || n.endsWith(".zip")
                    || n.endsWith(".tar.gz.enc"))) {
                throw ApiException.badRequest("staging_uploads_extension");
            }
        }
    }

    private static void requireSecretName(String name) {
        if (name == null || !SECRET_NAME.matcher(name).matches()) {
            throw ApiException.badRequest("secret_name_invalid");
        }
    }

    private static void requireSecretValue(String value) {
        if (value == null || value.isEmpty()) {
            throw ApiException.badRequest("secret_value_required");
        }
        if (value.length() > MAX_SECRET_CHARS) {
            throw ApiException.badRequest("secret_value_too_large");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0' || c == '\'') {
                throw ApiException.badRequest("secret_value_invalid");
            }
        }
    }
}
