package com.nebdev.devopsagent.saas.notification;

import com.nebdev.devopsagent.saas.shared.SaasProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Notifications multilingues (directive §13, §15). Gabarits = clés {@code mail.<template>.subject|body} des
 * bundles {@code i18n/messages_<lang>}. L'envoi SMTP a lieu <b>après</b> le commit (jamais d'e-mail pour une
 * action annulée). Sans SMTP configuré, le message est conservé avec le statut {@code LOGGED}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public interface Notifications extends JpaRepository<Notification, UUID> {
        @Query("select n from Notification n where lower(n.recipient) = lower(:r) order by n.createdAt desc")
        List<Notification> forRecipient(@Param("r") String recipient);
    }

    private final Notifications repo;
    private final MessageSource messages;
    private final ObjectProvider<JavaMailSender> mail;
    private final SaasProperties properties;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final com.nebdev.devopsagent.saas.shared.RlsContext rls;

    public NotificationService(Notifications repo, MessageSource messages, ObjectProvider<JavaMailSender> mail,
                               SaasProperties properties,
                               org.springframework.transaction.PlatformTransactionManager txManager,
                               com.nebdev.devopsagent.saas.shared.RlsContext rls) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.rls = rls;
        this.repo = repo;
        this.messages = messages;
        this.mail = mail;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Notification email(UUID organizationId, UUID userId, String to, String template, String locale,
                              Object... args) {
        Locale loc = Locale.forLanguageTag(locale == null ? "en" : locale);
        String subject = messages.getMessage("mail." + template + ".subject", args, template, loc);
        String body = messages.getMessage("mail." + template + ".body", args, "", loc);
        Notification n = repo.save(new Notification(organizationId, userId, to, template, loc.getLanguage(), subject, body));
        UUID id = n.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(id, to, subject, body);
                }
            });
        }
        return n;
    }

    private void deliver(UUID id, String to, String subject, String body) {
        JavaMailSender sender = mail.getIfAvailable();
        String status;
        String error = null;
        if (sender == null) {
            log.info("[mail non configuré] à={} sujet={}", to, subject);
            status = "LOGGED";
        } else {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(properties.getMailFrom());
                msg.setTo(to);
                msg.setSubject(subject);
                msg.setText(body);
                sender.send(msg);
                status = "SENT";
            } catch (RuntimeException e) {
                log.warn("Envoi e-mail échoué vers {} : {}", to, e.getMessage());
                status = "FAILED";
                error = e.getMessage();
            }
        }
        markDelivered(id, status, error);
    }

    private void markDelivered(UUID id, String status, String error) {
        // Code système de confiance : met à jour sa propre notification par identifiant.
        tx.executeWithoutResult(s -> { rls.enterPlatform(); repo.findById(id).ifPresent(n -> {
            switch (status) {
                case "SENT" -> n.sent(Instant.now());
                case "LOGGED" -> n.logged();
                default -> n.failed(error);
            }
        }); });
    }
}
