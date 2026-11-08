package com.nebdev.devopsagent.saas.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class BillingEntities {

    private BillingEntities() {}

    @Entity(name = "Plan")
    @Table(name = "plan")
    public static class Plan {
        @Id
        private String code;
        @Column(nullable = false)
        private String name;
        @Column(name = "server_quota", nullable = false)
        private int serverQuota;
        @Column(name = "member_quota", nullable = false)
        private int memberQuota;
        @JdbcTypeCode(SqlTypes.ARRAY)
        @Column(nullable = false, columnDefinition = "text[]")
        private String[] features;
        @Column(name = "trial_days", nullable = false)
        private int trialDays;
        @Column(name = "public", nullable = false)
        private boolean publicPlan;
        @Column(nullable = false)
        private boolean active;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;

        protected Plan() {}

        public String getCode() { return code; }
        public String getName() { return name; }
        public int getServerQuota() { return serverQuota; }
        public int getMemberQuota() { return memberQuota; }
        public List<String> getFeatures() { return Arrays.asList(features); }
        public int getTrialDays() { return trialDays; }
        public boolean isPublicPlan() { return publicPlan; }
        public boolean isActive() { return active; }
        public int getSortOrder() { return sortOrder; }
    }

    @Embeddable
    public static class PriceId implements Serializable {
        @Column(name = "plan_code")
        private String planCode;
        private String currency;
        private String period;

        protected PriceId() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof PriceId p && Objects.equals(planCode, p.planCode) && Objects.equals(currency, p.currency)
                    && Objects.equals(period, p.period);
        }

        @Override
        public int hashCode() { return Objects.hash(planCode, currency, period); }
    }

    @Entity(name = "PlanPrice")
    @Table(name = "plan_price")
    public static class PlanPrice {
        @EmbeddedId
        private PriceId id;
        @Column(name = "amount_minor", nullable = false)
        private long amountMinor;

        protected PlanPrice() {}

        public String getPlanCode() { return id.planCode; }
        public String getCurrency() { return id.currency; }
        public String getPeriod() { return id.period; }
        public long getAmountMinor() { return amountMinor; }
    }

    @Entity(name = "Subscription")
    @Table(name = "subscription")
    public static class Subscription {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false, unique = true)
        private UUID organizationId;
        @Column(name = "plan_code", nullable = false)
        private String planCode;
        @Column(nullable = false)
        private String status;
        @Column(nullable = false)
        private String currency;
        @Column(nullable = false)
        private String period;
        @Column(name = "trial_ends_at")
        private Instant trialEndsAt;
        @Column(name = "current_period_start", nullable = false)
        private Instant currentPeriodStart;
        @Column(name = "current_period_end", nullable = false)
        private Instant currentPeriodEnd;
        @Column(name = "cancel_at_period_end", nullable = false)
        private boolean cancelAtPeriodEnd;
        @Column(nullable = false)
        private String provider;
        @Column(name = "provider_ref")
        private String providerRef;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;
        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;
        @Version
        private long version;

        protected Subscription() {}

        Subscription(UUID organizationId, String planCode, String status, String currency, String period,
                     Instant trialEndsAt, Instant start, Instant end, String provider) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.planCode = planCode;
            this.status = status;
            this.currency = currency;
            this.period = period;
            this.trialEndsAt = trialEndsAt;
            this.currentPeriodStart = start;
            this.currentPeriodEnd = end;
            this.provider = provider;
            this.createdAt = Instant.now();
            this.updatedAt = createdAt;
        }

        void activate(String planCode, String period, Instant start, Instant end) {
            this.planCode = planCode;
            this.period = period;
            this.status = "ACTIVE";
            this.currentPeriodStart = start;
            this.currentPeriodEnd = end;
            this.cancelAtPeriodEnd = false;
            this.updatedAt = Instant.now();
        }

        void status(String s) { this.status = s; this.updatedAt = Instant.now(); }
        void cancelAtPeriodEnd(boolean v) { this.cancelAtPeriodEnd = v; this.updatedAt = Instant.now(); }
        void currency(String c) { this.currency = c; this.updatedAt = Instant.now(); }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public String getPlanCode() { return planCode; }
        public String getStatus() { return status; }
        public String getCurrency() { return currency; }
        public String getPeriod() { return period; }
        public Instant getTrialEndsAt() { return trialEndsAt; }
        public Instant getCurrentPeriodStart() { return currentPeriodStart; }
        public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
        public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
        public String getProvider() { return provider; }
    }

    @Entity(name = "Invoice")
    @Table(name = "invoice")
    public static class Invoice {
        @Id
        private UUID id;
        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;
        @Column(name = "subscription_id")
        private UUID subscriptionId;
        @Column(nullable = false, unique = true)
        private String number;
        @Column(nullable = false)
        private String currency;
        @Column(name = "amount_minor", nullable = false)
        private long amountMinor;
        @Column(nullable = false)
        private String status;
        @Column(nullable = false)
        private String description;
        @Column(name = "period_start")
        private Instant periodStart;
        @Column(name = "period_end")
        private Instant periodEnd;
        @Column(name = "issued_at", nullable = false)
        private Instant issuedAt;
        @Column(name = "due_at")
        private Instant dueAt;
        @Column(name = "paid_at")
        private Instant paidAt;
        @Column(nullable = false)
        private String provider;
        @Column(name = "provider_ref")
        private String providerRef;

        protected Invoice() {}

        Invoice(UUID organizationId, UUID subscriptionId, String number, String currency, long amountMinor,
                String description, Instant periodStart, Instant periodEnd, Instant dueAt, String provider) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.subscriptionId = subscriptionId;
            this.number = number;
            this.currency = currency;
            this.amountMinor = amountMinor;
            this.status = "OPEN";
            this.description = description;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
            this.issuedAt = Instant.now();
            this.dueAt = dueAt;
            this.provider = provider;
        }

        void paid(Instant at, String providerRef) {
            this.status = "PAID";
            this.paidAt = at;
            if (providerRef != null) this.providerRef = providerRef;
        }

        void status(String s) { this.status = s; }
        void providerRef(String ref) { this.providerRef = ref; }

        public UUID getId() { return id; }
        public UUID getOrganizationId() { return organizationId; }
        public UUID getSubscriptionId() { return subscriptionId; }
        public String getNumber() { return number; }
        public String getCurrency() { return currency; }
        public long getAmountMinor() { return amountMinor; }
        public String getStatus() { return status; }
        public String getDescription() { return description; }
        public Instant getPeriodStart() { return periodStart; }
        public Instant getPeriodEnd() { return periodEnd; }
        public Instant getIssuedAt() { return issuedAt; }
        public Instant getDueAt() { return dueAt; }
        public Instant getPaidAt() { return paidAt; }
        public String getProvider() { return provider; }
        public String getProviderRef() { return providerRef; }
    }

    @Entity(name = "PaymentEvent")
    @Table(name = "payment_event")
    public static class PaymentEvent {
        @Id
        private UUID id;
        @Column(nullable = false)
        private String provider;
        @Column(name = "provider_event_id", nullable = false)
        private String providerEventId;
        @Column(nullable = false)
        private String type;
        @JdbcTypeCode(SqlTypes.JSON)
        @Column(nullable = false)
        private Map<String, Object> payload;
        @Column(name = "received_at", nullable = false)
        private Instant receivedAt;
        @Column(name = "processed_at")
        private Instant processedAt;
        @Column(nullable = false)
        private String status;
        private String error;

        protected PaymentEvent() {}

        PaymentEvent(String provider, String eventId, String type, Map<String, Object> payload) {
            this.id = UUID.randomUUID();
            this.provider = provider;
            this.providerEventId = eventId;
            this.type = type;
            this.payload = payload;
            this.receivedAt = Instant.now();
            this.status = "RECEIVED";
        }

        void done(String status, String error) {
            this.status = status;
            this.error = error;
            this.processedAt = Instant.now();
        }

        public String getStatus() { return status; }
        public String getType() { return type; }
    }
}
