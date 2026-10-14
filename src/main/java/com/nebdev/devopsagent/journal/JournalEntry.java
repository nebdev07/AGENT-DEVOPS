package com.nebdev.devopsagent.journal;

import java.util.Map;

/**
 * Une ligne du journal d’un serveur : qui, quoi, quand, résultat.
 * Ne contient jamais le corps brut d’une requête (SQL, mots de passe, secrets).
 */
public record JournalEntry(
        String at,
        String operator,
        String host,
        String user,
        String method,
        String path,
        String action,
        String risk,
        String outcome,
        int status,
        long durationMs,
        Map<String, String> details,
        String error
) {

    public static final String OK = "OK";
    public static final String FAILED = "ECHEC";

    public boolean ok() {
        return OK.equals(outcome);
    }
}
