package com.nebdev.devopsagent.saas.ai;

import java.util.List;
import java.util.Map;

/**
 * Fournisseur de recommandations (ADR-054). Indépendant du modèle : l'IA conseille, elle n'exécute jamais.
 * Toute sortie est revalidée par {@link RecommendationValidator} avant d'être montrée ou transformée en brouillon.
 */
public interface AiProvider {

    String id();

    String model();

    /** Contexte assaini : faits techniques et constats d'audit, jamais d'adresse, de secret ni de clé. */
    record Context(String promptVersion, Map<String, Object> facts, List<Map<String, String>> findings,
                   List<String> allowedOperationTypes, String locale) {}

    record Recommendation(String title, String rationale, String risk, String operationType) {}

    record Advice(List<Recommendation> recommendations, String summary) {}

    Advice advise(Context context) throws AiUnavailableException;

    class AiUnavailableException extends Exception {
        public AiUnavailableException(String message) {
            super(message);
        }
    }
}
