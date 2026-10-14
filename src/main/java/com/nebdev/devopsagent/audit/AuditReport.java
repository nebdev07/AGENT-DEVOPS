package com.nebdev.devopsagent.audit;

import java.time.Instant;
import java.util.List;

/**
 * Rapport d’audit remis au client (BIZ-04). Calculé à partir d’une analyse en lecture seule.
 * @param score 0–100 ; {@code grade} A (≥ 90) à E (&lt; 40), plafonné à D en présence d’un constat critique
 */
public record AuditReport(
        String host,
        String hostname,
        Instant capturedAt,
        String os,
        int score,
        String grade,
        List<Finding> findings,
        List<String> strengths,
        Facts facts
) {

    public enum Severity {
        CRITIQUE("Critique", 30),
        ELEVE("Élevé", 15),
        MOYEN("Moyen", 7),
        FAIBLE("Faible", 2);

        private final String label;
        private final int penalty;

        Severity(String label, int penalty) {
            this.label = label;
            this.penalty = penalty;
        }

        public String label() {
            return label;
        }

        public int penalty() {
            return penalty;
        }

        Severity lower() {
            return this == FAIBLE ? FAIBLE : values()[ordinal() + 1];
        }
    }

    /**
     * @param category Accès, Réseau, Mises à jour, Sauvegardes, Ressources, Architecture, Intrusion
     */
    public record Finding(
            String id,
            Severity severity,
            String category,
            String title,
            String observed,
            String risk,
            String recommendation
    ) {}

    /** Fiche technique en annexe du rapport. */
    public record Facts(
            String cpu,
            String memory,
            String disk,
            String publicPorts,
            String firewall,
            String ssh,
            String containers
    ) {}

    public long count(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }
}
