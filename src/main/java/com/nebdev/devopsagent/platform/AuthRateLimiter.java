package com.nebdev.devopsagent.platform;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitation des tentatives sur les points d’entrée publics de l’authentification (S7, audit 2026-11-07).
 * Fenêtre glissante en mémoire : par (IP, e-mail) et par IP seule. Suffisant pour une instance ;
 * le SaaS (profil {@code saas}) a son propre limiteur adossé à la base.
 */
@Component
public class AuthRateLimiter {

    public static final int MAX_PER_KEY = 8;
    public static final int MAX_PER_IP = 40;
    public static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public AuthRateLimiter() {
        this(Clock.systemUTC());
    }

    AuthRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Enregistre une tentative ; lève 429 si une limite est dépassée. */
    public void check(String action, String ip, String email) {
        Instant now = clock.instant();
        String ipKey = "ip|" + (ip == null ? "" : ip);
        String key = action + "|" + ipKey + "|" + (email == null ? "" : email.strip().toLowerCase(Locale.ROOT));
        if (!allow(ipKey, MAX_PER_IP, now) || !allow(key, MAX_PER_KEY, now)) {
            throw new PlatformAccessException(429,
                    "Trop de tentatives. Réessaie dans " + WINDOW.toMinutes() + " minutes.");
        }
    }

    private boolean allow(String key, int max, Instant now) {
        Deque<Instant> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            Instant floor = now.minus(WINDOW);
            while (!q.isEmpty() && q.peekFirst().isBefore(floor)) {
                q.pollFirst();
            }
            if (q.size() >= max) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }
}
