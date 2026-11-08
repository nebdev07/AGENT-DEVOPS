package com.nebdev.devopsagent.journal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Texte lisible pour les rapports d’activité (UI, HTML, e-mail) à partir d’une entrée de journal.
 * N’invente rien : reformate seulement les champs déjà enregistrés.
 */
public final class JournalNarratives {

    private JournalNarratives() {}

    /** Phrase courte : action + contexte + résultat + durée. */
    public static String headline(JournalEntry e) {
        StringBuilder b = new StringBuilder();
        b.append(e.action() == null ? "Action" : e.action());
        String ctx = context(e.details());
        if (!ctx.isBlank()) {
            b.append(" — ").append(ctx);
        }
        b.append(" · ").append(e.ok() ? "réussi" : "échec ou refus");
        if (e.durationMs() > 0) {
            b.append(" (").append(duration(e.durationMs())).append(')');
        }
        return b.toString();
    }

    /** Lignes de détail pour affichage / export (sans secrets). */
    public static List<String> detailLines(JournalEntry e) {
        List<String> lines = new ArrayList<>();
        Map<String, String> d = e.details() == null ? Map.of() : e.details();
        if (d.containsKey("summary") && !d.get("summary").isBlank()) {
            lines.add(d.get("summary"));
        }
        putLine(lines, "Plateforme", first(d, "id", "platformId"));
        putLine(lines, "Applications", d.get("apps"));
        putLine(lines, "Commits déployés", d.get("commits"));
        putLine(lines, "Détail des services", d.get("services"));
        putLine(lines, "URLs", d.get("urls"));
        putLine(lines, "Routage", d.get("routing"));
        putLine(lines, "Nombre d’apps", d.get("count"));
        putLine(lines, "Composant", d.get("component"));
        putLine(lines, "Service", d.get("service"));
        putLine(lines, "Fichier", d.get("file"));
        putLine(lines, "Dépôt", d.get("repo"));
        putLine(lines, "Tâche", d.get("jobId"));
        putLine(lines, "OS", d.get("os"));
        putLine(lines, "Hostname", d.get("hostname"));
        putLine(lines, "Message", d.get("message"));
        putLine(lines, "Note", d.get("note"));
        if (e.user() != null && !e.user().isBlank()) {
            lines.add("SSH : " + e.user() + (e.host() == null || e.host().isBlank() ? "" : "@" + e.host()));
        }
        if (e.operator() != null && !e.operator().isBlank()) {
            lines.add("Opérateur : " + e.operator());
        }
        if (e.durationMs() > 0) {
            lines.add("Durée : " + duration(e.durationMs()));
        }
        // autres clés non déjà listées
        for (Map.Entry<String, String> en : d.entrySet()) {
            String k = en.getKey();
            if (k == null || skipKey(k) || en.getValue() == null || en.getValue().isBlank()) {
                continue;
            }
            lines.add(labelFor(k) + " : " + en.getValue());
        }
        if (e.error() != null && !e.error().isBlank()) {
            lines.add("Erreur : " + e.error().replace('\n', ' '));
        }
        return lines;
    }

    public static String duration(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        long sec = ms / 1000;
        if (sec < 60) {
            return sec + " s";
        }
        long min = sec / 60;
        long rem = sec % 60;
        if (min < 60) {
            return rem == 0 ? min + " min" : min + " min " + rem + " s";
        }
        long h = min / 60;
        long m = min % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    public static Map<String, String> withSummary(Map<String, String> details, String summary) {
        Map<String, String> out = new LinkedHashMap<>();
        if (summary != null && !summary.isBlank()) {
            out.put("summary", clip(summary.strip(), 500));
        }
        if (details != null) {
            details.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank() && !"summary".equals(k)) {
                    out.put(k, clip(v, 400));
                }
            });
        }
        return out;
    }

    private static String context(Map<String, String> d) {
        if (d == null || d.isEmpty()) {
            return "";
        }
        if (d.containsKey("summary") && !d.get("summary").isBlank()) {
            String s = d.get("summary");
            return s.length() > 120 ? s.substring(0, 117) + "…" : s;
        }
        String id = first(d, "id", "platformId", "component", "service", "file");
        return id == null ? "" : id;
    }

    private static String first(Map<String, String> d, String... keys) {
        for (String k : keys) {
            String v = d.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static void putLine(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + " : " + value);
        }
    }

    private static boolean skipKey(String k) {
        return switch (k) {
            case "summary", "id", "platformId", "component", "service", "file", "repo", "jobId",
                 "services", "urls", "apps", "commits", "routing", "count", "os", "hostname",
                 "message", "note", "confirm", "risk", "rotate", "name" -> true;
            default -> false;
        };
    }

    private static String labelFor(String key) {
        return switch (key) {
            case "removed" -> "Retiré";
            case "online" -> "En ligne";
            default -> key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
        };
    }

    private static String clip(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }
}
