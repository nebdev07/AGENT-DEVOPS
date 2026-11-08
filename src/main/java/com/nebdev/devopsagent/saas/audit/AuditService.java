package com.nebdev.devopsagent.saas.audit;

import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.CorrelationIdFilter;
import com.nebdev.devopsagent.saas.shared.Hashing;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Journal d'audit (ADR-053). Chaque événement porte {@code hash = sha256(prev_hash | contenu canonique)} :
 * toute modification ou suppression d'un événement casse la chaîne et se voit à {@link #verify}.
 * Les événements plateforme (sans organisation) ont leur propre chaîne.
 */
@Service
public class AuditService {

    static final UUID PLATFORM_CHAIN = new UUID(0, 0);
    static final String GENESIS = "0".repeat(64);

    public interface Events extends JpaRepository<AuditEvent, UUID> {
        @Query("select e from AuditEvent e where e.organizationId = :org order by e.seq asc")
        List<AuditEvent> chain(@Param("org") UUID org);

        @Query("select e from AuditEvent e where e.organizationId = :org and e.occurredAt >= :from order by e.seq desc")
        Page<AuditEvent> page(@Param("org") UUID org, @Param("from") Instant from, org.springframework.data.domain.Pageable p);
    }

    public record Entry(String action, String targetType, String targetId, String outcome, Map<String, Object> details) {
        public static Entry success(String action, String targetType, Object targetId, Map<String, Object> details) {
            return new Entry(action, targetType, targetId == null ? null : targetId.toString(), "SUCCESS", details);
        }

        public static Entry failure(String action, String targetType, Object targetId, Map<String, Object> details) {
            return new Entry(action, targetType, targetId == null ? null : targetId.toString(), "FAILURE", details);
        }

        public static Entry denied(String action, String targetType, Object targetId, Map<String, Object> details) {
            return new Entry(action, targetType, targetId == null ? null : targetId.toString(), "DENIED", details);
        }
    }

    public record Verification(boolean valid, long events, Long brokenAtSeq) {}

    @PersistenceContext
    private EntityManager em;
    private final Events events;
    private final RlsContext rls;

    public AuditService(Events events, RlsContext rls) {
        this.events = events;
        this.rls = rls;
    }

    /** Dans la transaction de l'action : l'événement et l'action sont validés ou annulés ensemble. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID organizationId, Entry entry) {
        append(organizationId, Actor.current().orElse(null), entry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID organizationId, Actor actor, Entry entry) {
        append(organizationId, actor, entry);
    }

    /**
     * Transaction indépendante : pour les refus d'accès et les échecs, dont la transaction principale est
     * annulée mais qui doivent rester tracés.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(UUID organizationId, Actor actor, Entry entry) {
        if (organizationId == null) {
            rls.enterPlatform();
        } else {
            rls.enterOrg(organizationId);
        }
        append(organizationId, actor, entry);
    }

    private void append(UUID organizationId, Actor actor, Entry entry) {
        UUID chainKey = organizationId == null ? PLATFORM_CHAIN : organizationId;
        // Verrou de la tête de chaîne : sérialise les ajouts d'une même organisation.
        em.createNativeQuery("insert into audit_chain_head (chain_key, last_seq, last_hash) values (:k, 0, :g) "
                        + "on conflict (chain_key) do nothing")
                .setParameter("k", chainKey).setParameter("g", GENESIS).executeUpdate();
        Object[] head = (Object[]) em.createNativeQuery(
                        "select last_seq, last_hash from audit_chain_head where chain_key = :k for update")
                .setParameter("k", chainKey).getSingleResult();
        long seq = ((Number) head[0]).longValue() + 1;
        String prev = (String) head[1];
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String actorType = actor == null ? "SYSTEM" : actor.auditType();
        String actorId = actor == null ? "system" : actor.auditId();
        Map<String, Object> details = entry.details() == null ? Map.of() : new LinkedHashMap<>(entry.details());
        String ip = currentIp();
        String cid = CorrelationIdFilter.current();
        String hash = hashOf(prev, organizationId, seq, actorType, actorId, entry, details, at);
        UUID id = UUID.randomUUID();
        em.persist(new AuditEvent(id, organizationId, seq, actorType, actorId, entry.action(), entry.targetType(),
                entry.targetId(), entry.outcome(), details, ip, cid, at, prev, hash));
        em.createNativeQuery("update audit_chain_head set last_seq = :s, last_hash = :h where chain_key = :k")
                .setParameter("s", seq).setParameter("h", hash).setParameter("k", chainKey).executeUpdate();
    }

    static String hashOf(String prev, UUID org, long seq, String actorType, String actorId, Entry entry,
                         Map<String, Object> details, Instant at) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("org", org == null ? "" : org.toString());
        content.put("seq", seq);
        content.put("actorType", actorType);
        content.put("actorId", actorId);
        content.put("action", entry.action());
        content.put("targetType", entry.targetType());
        content.put("targetId", entry.targetId());
        content.put("outcome", entry.outcome());
        content.put("details", details);
        content.put("at", at.toString());
        return Hashing.sha256Hex(prev + "|" + Hashing.canonicalJson(content));
    }

    /** Vérifie toute la chaîne d'une organisation (dans le contexte RLS de cette organisation). */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Verification verify(UUID organizationId) {
        List<AuditEvent> all = events.chain(organizationId);
        String prev = GENESIS;
        long expectedSeq = 1;
        for (AuditEvent e : all) {
            Entry entry = new Entry(e.getAction(), e.getTargetType(), e.getTargetId(), e.getOutcome(), e.getDetails());
            String expected = hashOf(prev, e.getOrganizationId(), e.getSeq(), e.getActorType(), e.getActorId(),
                    entry, e.getDetails(), e.getOccurredAt());
            if (e.getSeq() != expectedSeq || !e.getPrevHash().equals(prev) || !e.getHash().equals(expected)) {
                return new Verification(false, all.size(), e.getSeq());
            }
            prev = e.getHash();
            expectedSeq++;
        }
        return new Verification(true, all.size(), null);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Page<AuditEvent> page(UUID organizationId, Instant from, int page, int size) {
        return events.page(organizationId, from == null ? Instant.EPOCH : from,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200), Sort.unsorted()));
    }

    public static List<Map<String, Object>> view(List<AuditEvent> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditEvent e : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("seq", e.getSeq());
            m.put("occurredAt", e.getOccurredAt());
            m.put("actorType", e.getActorType());
            m.put("actorId", e.getActorId());
            m.put("action", e.getAction());
            m.put("targetType", e.getTargetType());
            m.put("targetId", e.getTargetId());
            m.put("outcome", e.getOutcome());
            m.put("details", e.getDetails());
            m.put("ip", e.getIp());
            m.put("correlationId", e.getCorrelationId());
            m.put("hash", e.getHash());
            out.add(m);
        }
        return out;
    }

    private static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();
        }
        return null;
    }
}
