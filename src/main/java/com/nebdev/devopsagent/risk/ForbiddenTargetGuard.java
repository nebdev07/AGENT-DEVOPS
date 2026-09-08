package com.nebdev.devopsagent.risk;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class ForbiddenTargetGuard {

    private static final Set<String> BLOCKED = Set.of(
            "54.37.156.11",
            "154.0.30.233",
            "arc-nwanyo.org"
    );

    public void assertAllowed(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        String key = host.trim().toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(key)) {
            throw new UnsafeActionException(
                    "Cette cible n’est pas autorisée : " + host);
        }
    }
}
