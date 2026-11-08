package com.nebdev.devopsagent.saas.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private UUID id;
    @Column(name = "organization_id")
    private UUID organizationId;
    @Column(name = "user_id")
    private UUID userId;
    @Column(nullable = false)
    private String channel = "EMAIL";
    @Column(nullable = false)
    private String recipient;
    @Column(name = "template_key", nullable = false)
    private String templateKey;
    @Column(nullable = false)
    private String locale;
    @Column(nullable = false)
    private String subject;
    @Column(nullable = false)
    private String body;
    @Column(nullable = false)
    private String status;
    private String error;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {}

    Notification(UUID organizationId, UUID userId, String recipient, String templateKey, String locale,
                 String subject, String body) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.userId = userId;
        this.recipient = recipient;
        this.templateKey = templateKey;
        this.locale = locale;
        this.subject = subject;
        this.body = body;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    void sent(Instant now) { this.status = "SENT"; this.sentAt = now; }
    void logged() { this.status = "LOGGED"; }
    void failed(String error) { this.status = "FAILED"; this.error = error; }

    public UUID getId() { return id; }
    public String getRecipient() { return recipient; }
    public String getTemplateKey() { return templateKey; }
    public String getLocale() { return locale; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
