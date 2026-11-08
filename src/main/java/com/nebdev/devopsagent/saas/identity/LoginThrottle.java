package com.nebdev.devopsagent.saas.identity;

import com.nebdev.devopsagent.saas.shared.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Limitation des tentatives adossée à la base (partagée entre instances) : par IP et par (action, e-mail).
 * Complète le verrouillage de compte après N échecs ({@link UserAccount#recordFailedLogin}).
 */
@Component
public class LoginThrottle {

    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_PER_IP = 50;
    static final int MAX_PER_IDENTITY = 10;

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void check(String action, String ip, String email) {
        Instant since = Instant.now().minus(WINDOW);
        String ipBucket = "ip:" + (ip == null ? "-" : ip);
        String idBucket = action + ":" + (email == null ? "-" : email.strip().toLowerCase(Locale.ROOT));
        if (count(ipBucket, since) >= MAX_PER_IP || count(idBucket, since) >= MAX_PER_IDENTITY) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", WINDOW.toMinutes());
        }
        insert(ipBucket);
        insert(idBucket);
    }

    private long count(String bucket, Instant since) {
        return ((Number) em.createNativeQuery("select count(*) from login_attempt where bucket = :b and at > :s")
                .setParameter("b", bucket).setParameter("s", since).getSingleResult()).longValue();
    }

    private void insert(String bucket) {
        em.createNativeQuery("insert into login_attempt (bucket) values (:b)").setParameter("b", bucket).executeUpdate();
    }

    @Scheduled(fixedDelay = 600_000)
    @Transactional
    public void purge() {
        em.createNativeQuery("delete from login_attempt where at < :s")
                .setParameter("s", Instant.now().minus(WINDOW.multipliedBy(2))).executeUpdate();
    }
}
