package com.nebdev.devopsagent.saas.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "organization")
public class Organization {

    public static final Set<String> CUSTOMER_TYPES = Set.of("SAAS", "SAAS_ASSISTED", "MANAGED");

    @Id
    private UUID id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, unique = true)
    private String slug;
    @Column(name = "customer_type", nullable = false)
    private String customerType = "SAAS";
    @Column(name = "default_locale", nullable = false)
    private String defaultLocale = "en";
    @Column(name = "time_zone", nullable = false)
    private String timeZone = "UTC";
    @Column(name = "billing_currency", nullable = false)
    private String billingCurrency = "USD";
    @Column(name = "require_four_eyes", nullable = false)
    private boolean requireFourEyes;
    @Column(nullable = false)
    private String status = "ACTIVE";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Organization() {}

    public Organization(String name, String slug, String locale, String timeZone, String currency) {
        this.id = UUID.randomUUID();
        this.name = name.strip();
        this.slug = slug;
        this.defaultLocale = locale;
        this.timeZone = timeZone == null || timeZone.isBlank() ? "UTC" : timeZone;
        this.billingCurrency = normalizeCurrency(currency);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    static String normalizeCurrency(String currency) {
        String c = currency == null || currency.isBlank() ? "USD" : currency.strip().toUpperCase(Locale.ROOT);
        try {
            return java.util.Currency.getInstance(c).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Devise ISO 4217 inconnue : " + currency);
        }
    }

    public boolean isActive() { return "ACTIVE".equals(status); }

    public void update(String name, String locale, String timeZone, String currency, Boolean fourEyes) {
        if (name != null && !name.isBlank()) this.name = name.strip();
        if (locale != null) this.defaultLocale = locale;
        if (timeZone != null && !timeZone.isBlank()) this.timeZone = timeZone;
        if (currency != null) this.billingCurrency = normalizeCurrency(currency);
        if (fourEyes != null) this.requireFourEyes = fourEyes;
        this.updatedAt = Instant.now();
    }

    public void setCustomerType(String type) {
        if (!CUSTOMER_TYPES.contains(type)) {
            throw new IllegalArgumentException("Type de client inconnu : " + type);
        }
        this.customerType = type;
        this.updatedAt = Instant.now();
    }

    public void setStatus(String status) { this.status = status; this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getCustomerType() { return customerType; }
    public String getDefaultLocale() { return defaultLocale; }
    public String getTimeZone() { return timeZone; }
    public String getBillingCurrency() { return billingCurrency; }
    public boolean isRequireFourEyes() { return requireFourEyes; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
