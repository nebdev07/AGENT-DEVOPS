package com.nebdev.devopsagent.saas.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class UserAccount {

    @Id
    private UUID id;
    @Column(nullable = false)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(name = "display_name", nullable = false)
    private String displayName = "";
    @Column(nullable = false)
    private String locale = "en";
    @Column(name = "time_zone", nullable = false)
    private String timeZone = "UTC";
    @Column(nullable = false)
    private String status = "ACTIVE";
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;
    @Column(name = "mfa_secret_enc")
    private byte[] mfaSecretEnc;
    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;
    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected UserAccount() {}

    public UserAccount(String email, String passwordHash, String displayName, String locale, String timeZone) {
        this.id = UUID.randomUUID();
        this.email = email.strip().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.displayName = displayName == null ? "" : displayName.strip();
        this.locale = Locales.normalize(locale);
        this.timeZone = timeZone == null || timeZone.isBlank() ? "UTC" : timeZone;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public boolean isActive() { return "ACTIVE".equals(status); }

    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }

    public void recordFailedLogin(int max, Instant lockUntil) {
        failedLogins++;
        if (failedLogins >= max) {
            lockedUntil = lockUntil;
            failedLogins = 0;
        }
        touch();
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLogins = 0;
        lockedUntil = null;
        lastLoginAt = now;
        touch();
    }

    public void changePassword(String hash) { this.passwordHash = hash; touch(); }

    public void verifyEmail(Instant now) { this.emailVerifiedAt = now; touch(); }

    public void setProfile(String displayName, String locale, String timeZone) {
        if (displayName != null) {
            this.displayName = displayName.strip();
        }
        if (locale != null) {
            this.locale = Locales.normalize(locale);
        }
        if (timeZone != null && !timeZone.isBlank()) {
            this.timeZone = timeZone;
        }
        touch();
    }

    public void setMfaSecret(byte[] enc) { this.mfaSecretEnc = enc; this.mfaEnabled = false; touch(); }

    public void enableMfa() { this.mfaEnabled = true; touch(); }

    public void disableMfa() { this.mfaEnabled = false; this.mfaSecretEnc = null; touch(); }

    public void setStatus(String status) { this.status = status; touch(); }

    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public String getLocale() { return locale; }
    public String getTimeZone() { return timeZone; }
    public String getStatus() { return status; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public byte[] getMfaSecretEnc() { return mfaSecretEnc; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
}
