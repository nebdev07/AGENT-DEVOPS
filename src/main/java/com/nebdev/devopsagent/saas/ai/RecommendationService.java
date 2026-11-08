package com.nebdev.devopsagent.saas.ai;

import com.nebdev.devopsagent.saas.operation.EnginePort;
import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.operation.OperationEntities;
import com.nebdev.devopsagent.saas.operation.OperationService;
import com.nebdev.devopsagent.saas.operation.OperationType;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.server.Server;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.server.ServerSnapshot;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.Hashing;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Recommandations par serveur (ADR-054) : contexte assaini → fournisseur (Claude si configuré, sinon règles) →
 * validation stricte → trace {@code ai_interaction}. Une recommandation peut devenir un brouillon d'opération,
 * qu'un humain doit ensuite proposer puis faire approuver : l'IA n'a aucun chemin vers l'exécution.
 */
@Service
public class RecommendationService {

    static final String PROMPT_KEY = "server_recommendations";
    static final String PROMPT_VERSION = "2026-11-07.1";

    @Entity(name = "Interaction")
    @Table(name = "ai_interaction")
    public static class Interaction {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(name = "server_id")
        private UUID serverId;
        @Column(nullable = false)
        private String provider;
        @Column(nullable = false)
        private String model;
        @Column(name = "prompt_key", nullable = false)
        private String promptKey;
        @Column(name = "prompt_version", nullable = false)
        private String promptVersion;
        @Column(name = "input_digest", nullable = false)
        private String inputDigest;
        @JdbcTypeCode(SqlTypes.JSON)
        private Map<String, Object> output;
        @Column(name = "schema_valid", nullable = false)
        private boolean schemaValid;
        private String error;
        @Column(name = "created_by")
        private UUID createdBy;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;

        protected Interaction() {}

        Interaction(UUID org, UUID server, String provider, String model, String digest, Map<String, Object> output,
                    boolean valid, String error, UUID by) {
            this.id = UUID.randomUUID();
            this.organizationId = org;
            this.serverId = server;
            this.provider = provider;
            this.model = model;
            this.promptKey = PROMPT_KEY;
            this.promptVersion = PROMPT_VERSION;
            this.inputDigest = digest;
            this.output = output;
            this.schemaValid = valid;
            this.error = error;
            this.createdBy = by;
            this.createdAt = Instant.now();
        }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public UUID getServerId() { return serverId; }
        public Map<String, Object> getOutput() { return output; }
    }

    public interface Interactions extends JpaRepository<Interaction, UUID> {}

    private final Interactions interactions;
    private final OrgAccess access;
    private final ServerService servers;
    private final OperationService operations;
    private final AnthropicAdvisor anthropic;
    private final RulesAdvisor rules;
    private final AuditService audit;
    private final EnginePort engine;

    public RecommendationService(Interactions interactions, OrgAccess access, ServerService servers,
                                 OperationService operations, AnthropicAdvisor anthropic, RulesAdvisor rules,
                                 AuditService audit, EnginePort engine) {
        this.interactions = interactions;
        this.access = access;
        this.servers = servers;
        this.operations = operations;
        this.anthropic = anthropic;
        this.rules = rules;
        this.audit = audit;
        this.engine = engine;
    }

    @Transactional
    public Map<String, Object> recommend(UUID orgId, UUID serverId, String locale) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.AI_USE);
        Server server = servers.require(orgId, serverId);
        ServerSnapshot latest = servers.latestSnapshotUnchecked(serverId)
                .orElseThrow(() -> ApiException.unprocessable("discovery_required"));
        AiProvider.Context context = context(latest, server, locale == null ? ctx.organization().getDefaultLocale() : locale);
        String digest = Hashing.canonicalHash(Map.of("facts", context.facts(), "findings", context.findings(),
                "prompt", PROMPT_VERSION));
        AiProvider used = anthropic.configured() ? anthropic : rules;
        AiProvider.Advice advice;
        String error = null;
        try {
            advice = used.advise(context);
        } catch (AiProvider.AiUnavailableException e) {
            error = e.getMessage();
            used = rules;
            advice = rules.advise(context);
        }
        RecommendationValidator.Result valid = RecommendationValidator.validate(advice, Set.copyOf(context.allowedOperationTypes()));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", RecommendationValidator.clean(advice.summary(), 300));
        output.put("recommendations", valid.accepted().stream().map(RecommendationService::recView).toList());
        output.put("rejected", valid.rejected());
        Interaction saved = interactions.save(new Interaction(orgId, serverId, used.id(), used.model(), digest, output,
                valid.rejected() == 0, error, ctx.userId()));
        audit.record(orgId, AuditService.Entry.success("ai.recommendations", "server", serverId,
                Map.of("interactionId", saved.getId().toString(), "provider", used.id(), "model", used.model(),
                        "accepted", valid.accepted().size(), "rejected", valid.rejected())));
        Map<String, Object> out = new LinkedHashMap<>(output);
        out.put("interactionId", saved.getId());
        out.put("provider", used.id());
        out.put("model", used.model());
        if (error != null) {
            out.put("fallbackReason", error);
        }
        return out;
    }

    /** Transforme la recommandation n° {@code index} d'une interaction en brouillon d'opération (jamais exécutable). */
    @Transactional
    @SuppressWarnings("unchecked")
    public OperationEntities.Operation draft(UUID orgId, UUID interactionId, int index) {
        OrgAccess.OrgContext ctx = access.require(orgId, OrgPermissions.OPERATION_PROPOSE);
        Interaction i = interactions.findById(interactionId).filter(x -> x.getOrganizationId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("ai_interaction"));
        List<Map<String, Object>> recs = (List<Map<String, Object>>) i.getOutput().getOrDefault("recommendations", List.of());
        if (index < 0 || index >= recs.size()) {
            throw ApiException.notFound("recommendation");
        }
        Object op = recs.get(index).get("operationType");
        OperationType type = op == null ? null : OperationType.parse(op.toString()).orElse(null);
        if (type == null || type.risk() == OperationType.Risk.DESTRUCTIVE) {
            throw ApiException.unprocessable("recommendation_not_actionable");
        }
        return operations.draftInContext(ctx, i.getServerId(), type, Map.of(),
                "AI: " + recs.get(index).get("title"));
    }

    AiProvider.Context context(ServerSnapshot latest, Server server, String locale) {
        // Le moteur résume son propre modèle : l'IA ne voit que des faits assainis (ADR-047, ADR-054).
        EnginePort.Summary summary = engine.summarize(latest.getPayload());
        Map<String, Object> facts = new LinkedHashMap<>(server.getCapabilities());
        facts.putAll(summary.facts());
        facts.put("protection", server.getProtection());
        List<Map<String, String>> findings = summary.findings();
        List<String> allowed = Arrays.stream(OperationType.values())
                .filter(t -> t.risk() == OperationType.Risk.CHANGE && t.component() != null || t == OperationType.RUN_BACKUP)
                .map(Enum::name).collect(Collectors.toList());
        return new AiProvider.Context(PROMPT_VERSION, facts, findings, allowed, locale);
    }

    private static Map<String, Object> recView(AiProvider.Recommendation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", r.title());
        m.put("rationale", r.rationale());
        m.put("risk", r.risk());
        m.put("operationType", r.operationType());
        return m;
    }
}
