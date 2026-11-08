package com.nebdev.devopsagent.saas.apitoken;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.Hashing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ApiTokenService {

    public static final String PREFIX = "apt";
    static final int MAX_DAYS = 365;

    public interface Tokens extends JpaRepository<ApiToken, UUID> {
        Optional<ApiToken> findByTokenHash(String hash);

        List<ApiToken> findByOrganizationIdOrderByCreatedAtDesc(UUID org);
    }

    public record Created(ApiToken token, String secret) {}

    private final Tokens tokens;
    private final OrgAccess access;
    private final AuditService audit;

    public ApiTokenService(Tokens tokens, OrgAccess access, AuditService audit) {
        this.tokens = tokens;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public Created create(UUID orgId, String name, Set<String> scopes, int days) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.APITOKEN_MANAGE);
        if (ctx.actor().type() != Actor.Type.USER) {
            throw ApiException.forbidden("token_cannot_create_token");
        }
        if (name == null || name.isBlank() || name.length() > 100) {
            throw ApiException.badRequest("invalid_name");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw ApiException.badRequest("scopes_required");
        }
        Set<String> clean = new LinkedHashSet<>();
        for (String s : scopes) {
            if (!OrgPermissions.TOKEN_SCOPES.contains(s)) {
                throw ApiException.badRequest("invalid_scope", s);
            }
            // Un jeton ne peut pas donner plus que ce que son créateur possède.
            if (!ctx.can(s)) {
                throw ApiException.forbidden("scope_exceeds_creator");
            }
            clean.add(s);
        }
        if (days < 1 || days > MAX_DAYS) {
            throw ApiException.badRequest("invalid_expiry", MAX_DAYS);
        }
        String secret = Hashing.randomToken(PREFIX);
        ApiToken t = tokens.save(new ApiToken(orgId, name.strip(), secret.substring(0, 12), Hashing.sha256Hex(secret),
                clean, ctx.userId(), Instant.now().plus(Duration.ofDays(days))));
        audit.record(orgId, AuditService.Entry.success("apitoken.created", "api_token", t.getId(),
                Map.of("name", t.getName(), "scopes", List.copyOf(clean), "expiresAt", t.getExpiresAt().toString())));
        return new Created(t, secret);
    }

    @Transactional(readOnly = true)
    public List<ApiToken> list(UUID orgId) {
        access.require(orgId, OrgPermissions.APITOKEN_MANAGE);
        return tokens.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    @Transactional
    public void revoke(UUID orgId, UUID tokenId) {
        access.require(orgId, OrgPermissions.APITOKEN_MANAGE);
        ApiToken t = tokens.findById(tokenId).filter(x -> x.getOrganizationId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("api_token"));
        t.revoke(Instant.now());
        audit.record(orgId, AuditService.Entry.success("apitoken.revoked", "api_token", tokenId, Map.of()));
    }

    /** Filtre de sécurité : jeton → acteur limité à son organisation et à ses scopes. */
    @Transactional
    public Optional<Actor> authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX + "_")) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return tokens.findByTokenHash(Hashing.sha256Hex(secret)).filter(t -> t.isValid(now)).map(t -> {
            if (t.getLastUsedAt() == null || t.getLastUsedAt().isBefore(now.minusSeconds(60))) {
                t.used(now);
            }
            return new Actor(Actor.Type.API_TOKEN, t.getCreatedBy(), null, null, t.getId(), t.getOrganizationId(),
                    Set.copyOf(t.getScopes()), "en");
        });
    }

    public static Map<String, Object> view(ApiToken t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("prefix", t.getTokenPrefix());
        m.put("scopes", t.getScopes());
        m.put("createdAt", t.getCreatedAt());
        m.put("expiresAt", t.getExpiresAt());
        m.put("revokedAt", t.getRevokedAt());
        m.put("lastUsedAt", t.getLastUsedAt());
        return m;
    }
}
