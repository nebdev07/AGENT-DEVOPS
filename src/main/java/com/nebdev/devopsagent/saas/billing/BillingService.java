package com.nebdev.devopsagent.saas.billing;

import com.nebdev.devopsagent.saas.audit.AuditService;
import com.nebdev.devopsagent.saas.organization.OrgAccess;
import com.nebdev.devopsagent.saas.organization.OrgPermissions;
import com.nebdev.devopsagent.saas.organization.Organization;
import com.nebdev.devopsagent.saas.organization.OrganizationPorts;
import com.nebdev.devopsagent.saas.organization.OrganizationRepositories;
import com.nebdev.devopsagent.saas.server.ServerService;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.ApiException;
import com.nebdev.devopsagent.saas.shared.RlsContext;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Abonnements, quotas et factures (directive §10). Volontairement simple tant que l'offre n'est pas validée :
 * essai → facture ouverte au changement de plan → activation au paiement (webhook signé ou rapprochement
 * administrateur) → impayé après échéance → suspension après délai de grâce. Idempotent par conception.
 */
@Service
public class BillingService implements ServerService.ServerQuotaPolicy, OrganizationPorts.Lifecycle,
        OrganizationPorts.MemberQuotaPolicy {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    public static final String TRIAL_PLAN = "STARTER";
    public static final Set<String> PERIODS = Set.of("MONTH", "YEAR");
    static final Duration DUE = Duration.ofDays(7);
    static final Duration GRACE = Duration.ofDays(14);

    public interface Plans extends JpaRepository<BillingEntities.Plan, String> {
        @Query("select p from Plan p where p.active = true and p.publicPlan = true order by p.sortOrder")
        List<BillingEntities.Plan> publicPlans();
    }

    public interface Prices extends JpaRepository<BillingEntities.PlanPrice, BillingEntities.PriceId> {
        @Query("select p from PlanPrice p where p.id.planCode = :plan")
        List<BillingEntities.PlanPrice> forPlan(@Param("plan") String plan);
    }

    public interface Subscriptions extends JpaRepository<BillingEntities.Subscription, UUID> {
        Optional<BillingEntities.Subscription> findByOrganizationId(UUID org);

        @Query("select s from Subscription s where s.status in ('TRIALING','ACTIVE','PAST_DUE')")
        List<BillingEntities.Subscription> live();
    }

    public interface Invoices extends JpaRepository<BillingEntities.Invoice, UUID> {
        List<BillingEntities.Invoice> findByOrganizationIdOrderByIssuedAtDesc(UUID org);

        Optional<BillingEntities.Invoice> findByNumber(String number);

        @Query("select i from Invoice i where i.organizationId = :org and i.status = 'OPEN'")
        List<BillingEntities.Invoice> open(@Param("org") UUID org);
    }

    public interface Events extends JpaRepository<BillingEntities.PaymentEvent, UUID> {
        boolean existsByProviderAndProviderEventId(String provider, String eventId);
    }

    @PersistenceContext
    private EntityManager em;
    private final Plans plans;
    private final Prices prices;
    private final Subscriptions subscriptions;
    private final Invoices invoices;
    private final Events events;
    private final OrganizationRepositories.Organizations orgs;
    private final OrganizationRepositories.Memberships memberships;
    private final org.springframework.beans.factory.ObjectProvider<OrgAccess> access;
    private final List<PaymentProvider> providers;
    private final AuditService audit;
    private final RlsContext rls;
    private final SaasProperties properties;

    public BillingService(Plans plans, Prices prices, Subscriptions subscriptions, Invoices invoices, Events events,
                          OrganizationRepositories.Organizations orgs, OrganizationRepositories.Memberships memberships,
                          org.springframework.beans.factory.ObjectProvider<OrgAccess> access,
                          List<PaymentProvider> providers, AuditService audit, RlsContext rls,
                          SaasProperties properties) {
        this.plans = plans;
        this.prices = prices;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.events = events;
        this.orgs = orgs;
        this.memberships = memberships;
        this.access = access;
        this.providers = providers;
        this.audit = audit;
        this.rls = rls;
        this.properties = properties;
    }

    // ───────────── Ports ─────────────

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void onOrganizationCreated(UUID organizationId, String currency) {
        BillingEntities.Plan trial = plans.findById(TRIAL_PLAN).orElseThrow();
        Instant now = Instant.now();
        Instant end = now.plus(Duration.ofDays(trial.getTrialDays()));
        subscriptions.save(new BillingEntities.Subscription(organizationId, trial.getCode(), "TRIALING",
                supportedCurrency(trial.getCode(), currency), "MONTH", end, now, end, provider().id()));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void assertCanAddServer(UUID organizationId, long currentCount) {
        BillingEntities.Plan plan = effectivePlan(organizationId);
        if (currentCount >= plan.getServerQuota()) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "server_quota_reached", plan.getServerQuota(), plan.getName());
        }
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void assertCanAddMember(UUID organizationId, long currentCount) {
        BillingEntities.Plan plan = effectivePlan(organizationId);
        if (currentCount >= plan.getMemberQuota()) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "member_quota_reached", plan.getMemberQuota(), plan.getName());
        }
    }

    private BillingEntities.Plan effectivePlan(UUID organizationId) {
        BillingEntities.Subscription s = subscriptions.findByOrganizationId(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.PAYMENT_REQUIRED, "no_subscription"));
        if ("CANCELLED".equals(s.getStatus())) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "subscription_cancelled");
        }
        return plans.findById(s.getPlanCode()).orElseThrow();
    }

    // ───────────── API client ─────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> publicCatalog() {
        return plans.publicPlans().stream().map(this::planView).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(UUID orgId) {
        access.getObject().require(orgId, OrgPermissions.BILLING_READ);
        BillingEntities.Subscription s = subscriptions.findByOrganizationId(orgId)
                .orElseThrow(() -> ApiException.notFound("subscription"));
        BillingEntities.Plan plan = plans.findById(s.getPlanCode()).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscription", subscriptionView(s));
        out.put("plan", planView(plan));
        long servers = ((Number) em.createNativeQuery("select count(*) from server where organization_id = :o")
                .setParameter("o", orgId).getSingleResult()).longValue();
        out.put("usage", Map.of("servers", servers, "serverQuota", plan.getServerQuota(),
                "members", memberships.countByOrganizationIdAndStatus(orgId, "ACTIVE"),
                "memberQuota", plan.getMemberQuota()));
        out.put("invoices", invoices.findByOrganizationIdOrderByIssuedAtDesc(orgId).stream().map(BillingService::invoiceView).toList());
        out.put("provider", provider().id());
        return out;
    }

    /** Changement de plan : facture ouverte ; le plan s'applique au paiement (jamais avant). */
    @Transactional
    public Map<String, Object> changePlan(UUID orgId, String planCode, String period) {
        OrgAccess.OrgContext ctx = access.getObject().require(orgId, OrgPermissions.BILLING_MANAGE);
        String per = period == null ? "MONTH" : period.toUpperCase();
        if (!PERIODS.contains(per)) {
            throw ApiException.badRequest("invalid_period");
        }
        BillingEntities.Plan plan = plans.findById(planCode == null ? "" : planCode.toUpperCase())
                .filter(p -> p.isActive() && p.isPublicPlan())
                .orElseThrow(() -> ApiException.notFound("plan"));
        BillingEntities.Subscription s = subscriptions.findByOrganizationId(orgId).orElseThrow();
        long servers = ((Number) em.createNativeQuery("select count(*) from server where organization_id = :o")
                .setParameter("o", orgId).getSingleResult()).longValue();
        if (servers > plan.getServerQuota()) {
            throw ApiException.unprocessable("plan_too_small", plan.getServerQuota());
        }
        String currency = supportedCurrency(plan.getCode(), ctx.organization().getBillingCurrency());
        long amount = price(plan.getCode(), currency, per);
        invoices.open(orgId).forEach(i -> i.status("VOID"));
        Instant start = Instant.now();
        Instant end = add(start, per);
        BillingEntities.Invoice inv = invoices.save(new BillingEntities.Invoice(orgId, s.getId(), nextNumber(), currency,
                amount, plan.getCode() + "|" + per, start, end, start.plus(DUE), provider().id()));
        PaymentProvider.Checkout checkout = provider().checkout(inv, ctx.actor().email());
        inv.providerRef(checkout.reference());
        s.currency(currency);
        audit.record(orgId, AuditService.Entry.success("billing.plan_change_requested", "invoice", inv.getId(),
                Map.of("plan", plan.getCode(), "period", per, "amountMinor", amount, "currency", currency)));
        Map<String, Object> out = new LinkedHashMap<>(invoiceView(inv));
        out.put("paymentUrl", checkout.paymentUrl());
        out.put("instructions", checkout.instructions());
        return out;
    }

    @Transactional
    public Map<String, Object> cancel(UUID orgId, boolean atPeriodEnd) {
        access.getObject().require(orgId, OrgPermissions.BILLING_MANAGE);
        BillingEntities.Subscription s = subscriptions.findByOrganizationId(orgId).orElseThrow();
        if (atPeriodEnd) {
            s.cancelAtPeriodEnd(true);
        } else {
            s.status("CANCELLED");
        }
        audit.record(orgId, AuditService.Entry.success("billing.cancelled", "subscription", s.getId(),
                Map.of("atPeriodEnd", atPeriodEnd)));
        return subscriptionView(s);
    }

    // ───────────── Paiement (webhook / administrateur) ─────────────

    /** Idempotent : un même événement (prestataire, identifiant) n'est appliqué qu'une fois. */
    @Transactional
    public String handleWebhook(String providerId, Map<String, String> headers, byte[] body) {
        PaymentProvider p = providers.stream().filter(x -> x.id().equals(providerId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("payment_provider"));
        Optional<PaymentProvider.WebhookEvent> event = p.verify(headers, body);
        if (event.isEmpty()) {
            log.warn("Webhook {} rejeté : signature absente ou invalide", providerId);
            throw ApiException.unauthorized("invalid_signature");
        }
        rls.enterPlatform();
        PaymentProvider.WebhookEvent e = event.get();
        if (events.existsByProviderAndProviderEventId(providerId, e.eventId())) {
            return "duplicate";
        }
        // La contrainte unique (provider, provider_event_id) reste le dernier rempart en cas de course.
        BillingEntities.PaymentEvent stored = events.saveAndFlush(
                new BillingEntities.PaymentEvent(providerId, e.eventId(), e.type(), e.raw()));
        if (!"invoice.paid".equals(e.type()) || e.invoiceNumber() == null) {
            stored.done("IGNORED", null);
            return "ignored";
        }
        Optional<BillingEntities.Invoice> inv = invoices.findByNumber(e.invoiceNumber());
        if (inv.isEmpty()) {
            stored.done("FAILED", "unknown_invoice");
            return "unknown_invoice";
        }
        markPaidInternal(inv.get(), e.providerRef(), null);
        stored.done("PROCESSED", null);
        return "processed";
    }

    @Transactional
    public Map<String, Object> markPaidByAdmin(UUID invoiceId, String reference) {
        Actor actor = Actor.require();
        if (!actor.isPlatformAdmin()) {
            throw ApiException.forbidden("platform_admin_only");
        }
        rls.enterPlatform();
        BillingEntities.Invoice inv = invoices.findById(invoiceId).orElseThrow(() -> ApiException.notFound("invoice"));
        markPaidInternal(inv, reference, actor);
        return invoiceView(inv);
    }

    private void markPaidInternal(BillingEntities.Invoice inv, String reference, Actor actor) {
        if ("PAID".equals(inv.getStatus())) {
            return;
        }
        if (!"OPEN".equals(inv.getStatus())) {
            throw ApiException.conflict("invoice_not_open", inv.getStatus());
        }
        inv.paid(Instant.now(), reference);
        BillingEntities.Subscription s = subscriptions.findById(inv.getSubscriptionId()).orElseThrow();
        String[] planAndPeriod = inv.getDescription().split("\\|");
        String planCode = planAndPeriod[0];
        String period = planAndPeriod.length > 1 ? planAndPeriod[1] : "MONTH";
        s.activate(planCode, period, inv.getPeriodStart(), inv.getPeriodEnd());
        orgs.findById(inv.getOrganizationId()).filter(o -> "SUSPENDED".equals(o.getStatus()))
                .ifPresent(o -> o.setStatus("ACTIVE"));
        audit.record(inv.getOrganizationId(), actor, AuditService.Entry.success("billing.invoice_paid", "invoice",
                inv.getId(), Map.of("number", inv.getNumber(), "plan", planCode, "reference", reference == null ? "" : reference)));
    }

    // ───────────── Cycle de vie (tâche planifiée) ─────────────

    @Scheduled(cron = "0 15 * * * *")
    @Transactional
    public void lifecycle() {
        rls.enterPlatform();
        Instant now = Instant.now();
        for (BillingEntities.Subscription s : subscriptions.live()) {
            if (now.isBefore(s.getCurrentPeriodEnd())) {
                continue;
            }
            if (s.isCancelAtPeriodEnd()) {
                s.status("CANCELLED");
                audit.record(s.getOrganizationId(), null, AuditService.Entry.success("billing.ended", "subscription", s.getId(), Map.of()));
                continue;
            }
            if (!"PAST_DUE".equals(s.getStatus())) {
                String previous = s.getStatus();
                s.status("PAST_DUE");
                if ("ACTIVE".equals(previous) && invoices.open(s.getOrganizationId()).isEmpty()) {
                    renewalInvoice(s);
                }
                audit.record(s.getOrganizationId(), null, AuditService.Entry.success("billing.past_due", "subscription", s.getId(), Map.of()));
            } else if (now.isAfter(s.getCurrentPeriodEnd().plus(GRACE))) {
                orgs.findById(s.getOrganizationId()).filter(Organization::isActive).ifPresent(o -> {
                    o.setStatus("SUSPENDED");
                    audit.record(o.getId(), null, AuditService.Entry.success("billing.suspended", "organization", o.getId(), Map.of()));
                });
            }
        }
    }

    private void renewalInvoice(BillingEntities.Subscription s) {
        long amount = price(s.getPlanCode(), s.getCurrency(), s.getPeriod());
        Instant start = s.getCurrentPeriodEnd();
        invoices.save(new BillingEntities.Invoice(s.getOrganizationId(), s.getId(), nextNumber(), s.getCurrency(), amount,
                s.getPlanCode() + "|" + s.getPeriod(), start, add(start, s.getPeriod()), start.plus(DUE), provider().id()));
    }

    // ───────────── Outils ─────────────

    private PaymentProvider provider() {
        String wanted = properties.getBilling().getProvider();
        return providers.stream().filter(p -> p.id().equals(wanted)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Prestataire de paiement inconnu : " + wanted));
    }

    private String supportedCurrency(String plan, String wanted) {
        List<BillingEntities.PlanPrice> list = prices.forPlan(plan);
        return list.stream().map(BillingEntities.PlanPrice::getCurrency).filter(c -> c.equals(wanted)).findFirst()
                .orElse("USD");
    }

    private long price(String plan, String currency, String period) {
        return prices.forPlan(plan).stream()
                .filter(p -> p.getCurrency().equals(currency) && p.getPeriod().equals(period))
                .map(BillingEntities.PlanPrice::getAmountMinor).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("price_unavailable", plan, currency, period));
    }

    private String nextNumber() {
        long n = ((Number) em.createNativeQuery("select nextval('invoice_number_seq')").getSingleResult()).longValue();
        return "AP-" + java.time.Year.now(ZoneOffset.UTC).getValue() + "-" + n;
    }

    private static Instant add(Instant start, String period) {
        return "YEAR".equals(period)
                ? start.atZone(ZoneOffset.UTC).plusYears(1).toInstant()
                : start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
    }

    private Map<String, Object> planView(BillingEntities.Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getCode());
        m.put("name", p.getName());
        m.put("serverQuota", p.getServerQuota());
        m.put("memberQuota", p.getMemberQuota());
        m.put("features", p.getFeatures());
        m.put("trialDays", p.getTrialDays());
        m.put("prices", prices.forPlan(p.getCode()).stream().map(x -> Map.of("currency", x.getCurrency(),
                "period", x.getPeriod(), "amountMinor", x.getAmountMinor())).toList());
        return m;
    }

    public static Map<String, Object> subscriptionView(BillingEntities.Subscription s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("plan", s.getPlanCode());
        m.put("status", s.getStatus());
        m.put("currency", s.getCurrency());
        m.put("period", s.getPeriod());
        m.put("trialEndsAt", s.getTrialEndsAt());
        m.put("currentPeriodStart", s.getCurrentPeriodStart());
        m.put("currentPeriodEnd", s.getCurrentPeriodEnd());
        m.put("cancelAtPeriodEnd", s.isCancelAtPeriodEnd());
        return m;
    }

    public static Map<String, Object> invoiceView(BillingEntities.Invoice i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("number", i.getNumber());
        m.put("status", i.getStatus());
        m.put("currency", i.getCurrency());
        m.put("amountMinor", i.getAmountMinor());
        m.put("description", i.getDescription());
        m.put("periodStart", i.getPeriodStart());
        m.put("periodEnd", i.getPeriodEnd());
        m.put("issuedAt", i.getIssuedAt());
        m.put("dueAt", i.getDueAt());
        m.put("paidAt", i.getPaidAt());
        return m;
    }
}
