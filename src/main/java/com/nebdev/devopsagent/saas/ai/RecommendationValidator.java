package com.nebdev.devopsagent.saas.ai;

import com.nebdev.devopsagent.saas.operation.OperationType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validation stricte et déterministe d'une sortie de modèle (ADR-054). La décision de sécurité reste ici, dans le
 * code : un type d'opération inconnu, DESTRUCTIVE ou hors liste blanche est retiré ; les textes sont bornés.
 */
public final class RecommendationValidator {

    static final int MAX_ITEMS = 8;
    static final Set<String> RISKS = Set.of("LOW", "MEDIUM", "HIGH");

    public record Result(List<AiProvider.Recommendation> accepted, int rejected) {}

    private RecommendationValidator() {}

    public static Result validate(AiProvider.Advice advice, Set<String> allowed) {
        List<AiProvider.Recommendation> ok = new ArrayList<>();
        int rejected = 0;
        if (advice == null || advice.recommendations() == null) {
            return new Result(List.of(), 0);
        }
        for (AiProvider.Recommendation r : advice.recommendations()) {
            if (ok.size() >= MAX_ITEMS) {
                rejected++;
                continue;
            }
            String title = clean(r.title(), 160);
            String rationale = clean(r.rationale(), 400);
            String risk = r.risk() == null ? "MEDIUM" : r.risk().strip().toUpperCase(Locale.ROOT);
            if (title.isEmpty() || !RISKS.contains(risk)) {
                rejected++;
                continue;
            }
            String op = r.operationType() == null || r.operationType().isBlank() || "null".equalsIgnoreCase(r.operationType())
                    ? null : r.operationType().strip().toUpperCase(Locale.ROOT);
            if (op != null) {
                var type = OperationType.parse(op);
                if (type.isEmpty() || type.get().risk() == OperationType.Risk.DESTRUCTIVE || !allowed.contains(op)) {
                    rejected++;
                    continue;
                }
            }
            ok.add(new AiProvider.Recommendation(title, rationale, risk, op));
        }
        return new Result(List.copyOf(ok), rejected);
    }

    static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String c = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
        return c.length() > max ? c.substring(0, max) : c;
    }
}
