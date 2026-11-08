package com.nebdev.devopsagent.saas.deployment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.saas.credential.GitCredentialService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.stack.AppSpec;
import com.nebdev.devopsagent.stack.PlatformServiceSpec;
import com.nebdev.devopsagent.stack.PlatformSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD des fiches de déploiement SaaS. Les secrets d'env plateforme restent dans {@code spec.env}
 * (masqués à la lecture API). Les dépôts Git SSH exigent une Deploy key enregistrée
 * ({@link GitCredentialService}) avant de proposer un DEPLOY_*.
 */
@Service
public class DeploymentSpecService {

    private final DeploymentSpecRepository specs;
    private final OrgAccess access;
    private final ServerService servers;
    private final GitCredentialService gitCredentials;
    private final ObjectMapper mapper;

    public DeploymentSpecService(DeploymentSpecRepository specs, OrgAccess access, ServerService servers,
                                 GitCredentialService gitCredentials, ObjectMapper mapper) {
        this.specs = specs;
        this.access = access;
        this.servers = servers;
        this.gitCredentials = gitCredentials;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<DeploymentSpec> list(UUID orgId, UUID serverId) {
        access.require(orgId, OrgPermissions.SERVER_READ);
        servers.require(orgId, serverId);
        return specs.findByOrganizationIdAndServerIdOrderBySlugAsc(orgId, serverId);
    }

    @Transactional(readOnly = true)
    public DeploymentSpec get(UUID orgId, UUID specId) {
        access.require(orgId, OrgPermissions.SERVER_READ);
        return require(orgId, specId);
    }

    @Transactional
    public DeploymentSpec createPlatform(UUID orgId, UUID serverId, Map<String, Object> body) {
        access.require(orgId, OrgPermissions.SERVER_MANAGE);
        var server = servers.require(orgId, serverId);
        PlatformSpec platform = normalizePlatform(body, server.getHost());
        if (specs.existsByOrganizationIdAndServerIdAndKindAndSlug(orgId, serverId, DeploymentSpec.KIND_PLATFORM, platform.id())) {
            throw ApiException.conflict("deployment_slug_taken", platform.id());
        }
        return specs.save(new DeploymentSpec(orgId, serverId, DeploymentSpec.KIND_PLATFORM, platform.id(),
                toMap(platform)));
    }

    @Transactional
    public DeploymentSpec createApp(UUID orgId, UUID serverId, Map<String, Object> body) {
        access.require(orgId, OrgPermissions.SERVER_MANAGE);
        servers.require(orgId, serverId);
        AppSpec app = normalizeApp(body);
        assertHostnameFree(orgId, serverId, app.id(), app.hostname());
        if (specs.existsByOrganizationIdAndServerIdAndKindAndSlug(orgId, serverId, DeploymentSpec.KIND_APP, app.id())) {
            throw ApiException.conflict("deployment_slug_taken", app.id());
        }
        return specs.save(new DeploymentSpec(orgId, serverId, DeploymentSpec.KIND_APP, app.id(), toMap(app)));
    }

    @Transactional
    public DeploymentSpec update(UUID orgId, UUID specId, Map<String, Object> body) {
        access.require(orgId, OrgPermissions.SERVER_MANAGE);
        DeploymentSpec row = require(orgId, specId);
        var server = servers.require(orgId, row.getServerId());
        if (DeploymentSpec.KIND_PLATFORM.equals(row.getKind())) {
            PlatformSpec platform = normalizePlatform(body, server.getHost());
            if (!platform.id().equals(row.getSlug())) {
                throw ApiException.badRequest("slug_immutable");
            }
            row.replaceSpec(toMap(platform));
        } else {
            AppSpec app = normalizeApp(body);
            if (!app.id().equals(row.getSlug())) {
                throw ApiException.badRequest("slug_immutable");
            }
            assertHostnameFree(orgId, row.getServerId(), app.id(), app.hostname());
            row.replaceSpec(toMap(app));
        }
        return specs.save(row);
    }

    @Transactional
    public void delete(UUID orgId, UUID specId) {
        access.require(orgId, OrgPermissions.SERVER_MANAGE);
        specs.delete(require(orgId, specId));
    }

    /**
     * Figé les paramètres d'une opération DEPLOY_* : charge la fiche, vérifie le serveur, refuse SSH Git,
     * injecte {@code spec} canonique pour le plan haché.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> freezeForOperation(UUID orgId, UUID serverId, String kind, Map<String, Object> parameters) {
        access.enter(orgId);
        if (parameters == null || parameters.get("specId") == null) {
            throw ApiException.badRequest("deployment_spec_required");
        }
        UUID specId;
        try {
            specId = UUID.fromString(String.valueOf(parameters.get("specId")));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("deployment_spec_required");
        }
        DeploymentSpec row = require(orgId, specId);
        if (!row.getServerId().equals(serverId)) {
            throw ApiException.unprocessable("deployment_server_mismatch");
        }
        if (!kind.equals(row.getKind())) {
            throw ApiException.unprocessable("deployment_kind_mismatch");
        }
        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put("specId", row.getId().toString());
        frozen.put("slug", row.getSlug());
        frozen.put("specVersion", row.getSpecVersion());
        frozen.put("spec", Map.copyOf(row.getSpec()));
        Object serviceId = parameters.get("serviceId");
        if (serviceId != null && !String.valueOf(serviceId).isBlank()) {
            frozen.put("serviceId", String.valueOf(serviceId).trim().toLowerCase());
        }
        // Paramètres d'opération hors fiche (SQL, restore, secrets…) : figés tels quels pour le plan hashé.
        for (String key : List.of(
                "sql", "foreignKeyChecks", "uploadsSubdir",
                "sqlStagingId", "uploadsStagingId", "secretStagingId",
                "secretName", "restart")) {
            Object v = parameters.get(key);
            if (v == null) {
                continue;
            }
            if (v instanceof String s) {
                if (!s.isBlank()) {
                    frozen.put(key, s.trim());
                }
            } else {
                frozen.put(key, v);
            }
        }
        Map<String, Object> result = Map.copyOf(frozen);
        gitCredentials.assertDeployGitAccess(orgId, result);
        return result;
    }

    public Map<String, Object> view(DeploymentSpec s, boolean maskSecrets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("organizationId", s.getOrganizationId());
        m.put("serverId", s.getServerId());
        m.put("kind", s.getKind());
        m.put("slug", s.getSlug());
        m.put("specVersion", s.getSpecVersion());
        m.put("updatedAt", s.getUpdatedAt());
        if (maskSecrets && DeploymentSpec.KIND_PLATFORM.equals(s.getKind())) {
            m.put("spec", toMap(toPlatform(s.getSpec()).masked()));
        } else {
            m.put("spec", s.getSpec());
        }
        return m;
    }

    private Map<String, Object> toMap(Object value) {
        return mapper.convertValue(value, new TypeReference<>() {});
    }

    private DeploymentSpec require(UUID orgId, UUID specId) {
        return specs.findByOrganizationIdAndId(orgId, specId)
                .orElseThrow(() -> ApiException.notFound("deployment_spec"));
    }

    private void assertHostnameFree(UUID orgId, UUID serverId, String slug, String hostname) {
        for (DeploymentSpec other : specs.findByOrganizationIdAndServerIdOrderBySlugAsc(orgId, serverId)) {
            if (!DeploymentSpec.KIND_APP.equals(other.getKind()) || other.getSlug().equals(slug)) {
                continue;
            }
            AppSpec app = toApp(other.getSpec());
            if (app.hostname().equalsIgnoreCase(hostname)) {
                throw ApiException.conflict("hostname_taken", hostname);
            }
        }
    }

    private PlatformSpec normalizePlatform(Map<String, Object> body, String vpsIp) {
        try {
            String id = str(body.get("id"));
            String domain = str(body.get("baseDomain"));
            String routing = str(body.getOrDefault("routing", PlatformSpec.ROUTING_HOST));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> services = (List<Map<String, Object>>) body.get("services");
            if (services == null) {
                throw ApiException.badRequest("invalid_platform_spec");
            }
            List<PlatformServiceSpec> parsed = services.stream()
                    .map(s -> mapper.convertValue(s, PlatformServiceSpec.class))
                    .toList();
            @SuppressWarnings("unchecked")
            List<String> aliases = body.get("aliases") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
            Boolean retain = body.get("retainSources") instanceof Boolean b ? b : Boolean.TRUE;
            return PlatformSpec.normalized(id, domain, routing, parsed, aliases, retain, vpsIp);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_platform_spec", e.getMessage());
        }
    }

    private AppSpec normalizeApp(Map<String, Object> body) {
        try {
            Integer port = body.get("port") instanceof Number n ? n.intValue() : null;
            return AppSpec.normalized(str(body.get("id")), str(body.get("gitUrl")), str(body.get("gitRef")),
                    str(body.get("hostname")), port);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_app_spec", e.getMessage());
        }
    }

    PlatformSpec toPlatform(Map<String, Object> spec) {
        return mapper.convertValue(spec, PlatformSpec.class);
    }

    AppSpec toApp(Map<String, Object> spec) {
        return mapper.convertValue(spec, AppSpec.class);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
