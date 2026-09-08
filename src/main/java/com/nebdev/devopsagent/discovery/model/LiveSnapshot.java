package com.nebdev.devopsagent.discovery.model;

/**
 * Distingue une discovery SSH réelle d’une fixture / IP de documentation.
 */
public final class LiveSnapshot {

    private LiveSnapshot() {}

    public static boolean isLive(ServerSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        String source = snapshot.source() == null ? "" : snapshot.source();
        if (source.startsWith("fixture") || source.equals("fixture")) {
            return false;
        }
        String host = snapshot.host() == null ? "" : snapshot.host().trim();
        return !host.isBlank()
                && !host.equals("fixture")
                && !host.startsWith("203.0.113.")
                && source.startsWith("ssh:");
    }
}
