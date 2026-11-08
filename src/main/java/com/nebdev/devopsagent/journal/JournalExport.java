package com.nebdev.devopsagent.journal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Export du journal d’un serveur : CSV (tableur) ou page HTML imprimable remise au client. */
public final class JournalExport {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRENCH)
            .withZone(ZoneId.of("UTC"));

    private JournalExport() {}

    /** CSV séparé par « ; », précédé d’un BOM pour qu’Excel lise l’UTF-8. */
    public static String csv(List<JournalEntry> entries) {
        StringBuilder b = new StringBuilder("\uFEFF");
        b.append("Date (UTC);Opérateur;Serveur;Utilisateur SSH;Action;Type;Résultat;Code;Durée;Résumé;Détails;Erreur\r\n");
        for (JournalEntry e : entries) {
            b.append(String.join(";",
                    cell(date(e.at())),
                    cell(e.operator()),
                    cell(e.host()),
                    cell(e.user()),
                    cell(e.action()),
                    cell(riskLabel(e.risk())),
                    cell(outcomeLabel(e)),
                    cell(String.valueOf(e.status())),
                    cell(JournalNarratives.duration(e.durationMs())),
                    cell(summaryOf(e)),
                    cell(String.join(" | ", JournalNarratives.detailLines(e))),
                    cell(e.error())
            )).append("\r\n");
        }
        return b.toString();
    }

    /** Résumé texte pour e-mail (alertes VPS) — détail de chaque action. */
    public static String plainSummary(String host, String client, List<JournalEntry> entries) {
        String title = host == null || host.isBlank() ? "Poste opérateur" : host.strip();
        long changes = entries.stream().filter(e -> ActionCatalog.Risk.CHANGE.name().equals(e.risk())).count();
        long failed = entries.stream().filter(e -> !e.ok()).count();
        StringBuilder b = new StringBuilder();
        b.append("Rapport d’activité VPS Autopilot\n");
        if (client != null && !client.isBlank()) {
            b.append("Client : ").append(client.strip()).append('\n');
        }
        b.append("Serveur : ").append(title).append('\n');
        b.append(entries.size()).append(" action(s), dont ").append(changes)
                .append(" modification(s) et ").append(failed).append(" échec(s).\n");
        b.append("Heures en UTC.\n\n");
        b.append("—— Chronologie ——\n\n");
        int n = 0;
        for (JournalEntry e : entries) {
            if (n++ >= 60) {
                b.append("… (").append(entries.size() - 60).append(" autres actions — export HTML pour la suite)\n");
                break;
            }
            b.append(date(e.at())).append('\n');
            b.append("  ").append(JournalNarratives.headline(e)).append('\n');
            for (String line : JournalNarratives.detailLines(e)) {
                b.append("  · ").append(line).append('\n');
            }
            b.append('\n');
        }
        b.append("Généré automatiquement depuis le menu Activité du cockpit.\n");
        return b.toString();
    }

    public static String html(String host, String client, List<JournalEntry> entries) {
        String title = host == null || host.isBlank() ? "Réglages du poste" : host.strip();
        StringBuilder b = new StringBuilder(12_000);
        b.append("<!DOCTYPE html>\n<html lang=\"fr\">\n<head>\n<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Rapport d’activité — ").append(esc(title)).append("</title>\n<style>\n")
                .append(CSS).append("</style>\n</head>\n<body>\n<main>\n<header>\n")
                .append("<p class=\"kicker\">Rapport d’activité</p>\n<h1>").append(esc(title)).append("</h1>\n<p class=\"meta\">");
        if (client != null && !client.isBlank()) {
            b.append("Préparé pour <strong>").append(esc(client.strip())).append("</strong> · ");
        }
        long changes = entries.stream().filter(e -> ActionCatalog.Risk.CHANGE.name().equals(e.risk())).count();
        long failed = entries.stream().filter(e -> !e.ok()).count();
        long reads = entries.stream().filter(e -> ActionCatalog.Risk.READ.name().equals(e.risk())).count();
        b.append(entries.size()).append(" action(s) : ").append(changes).append(" modification(s), ")
                .append(reads).append(" lecture(s), ")
                .append(failed).append(" échec(s) ou refus. Heures en UTC.</p>\n</header>\n");
        if (entries.isEmpty()) {
            b.append("<p>Aucune action enregistrée.</p>\n");
        } else {
            b.append("<section class=\"timeline\">\n");
            for (JournalEntry e : entries) {
                b.append("<article class=\"").append(e.ok() ? "ok" : "ko").append("\">\n")
                        .append("<header><time>").append(esc(date(e.at()))).append("</time>")
                        .append("<span class=\"badge\">").append(esc(riskLabel(e.risk()))).append("</span>")
                        .append("<span class=\"badge ").append(e.ok() ? "ok" : "ko").append("\">")
                        .append(esc(outcomeLabel(e))).append("</span></header>\n")
                        .append("<h2>").append(esc(JournalNarratives.headline(e))).append("</h2>\n")
                        .append("<ul class=\"details\">\n");
                for (String line : JournalNarratives.detailLines(e)) {
                    b.append("<li>").append(esc(line)).append("</li>\n");
                }
                b.append("</ul>\n</article>\n");
            }
            b.append("</section>\n");
        }
        b.append("<footer>Journal tenu automatiquement par VPS Autopilot : chaque action lancée depuis la console est ")
                .append("enregistrée avec son résultat et un résumé. Les requêtes SQL et les mots de passe ne sont jamais recopiés.</footer>\n")
                .append("</main>\n</body>\n</html>\n");
        return b.toString();
    }

    static String summaryOf(JournalEntry e) {
        if (e.details() != null && e.details().get("summary") != null && !e.details().get("summary").isBlank()) {
            return e.details().get("summary");
        }
        return JournalNarratives.headline(e);
    }

    static String date(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            return DATE.format(Instant.parse(iso));
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    static String riskLabel(String risk) {
        if (risk == null) {
            return "";
        }
        try {
            return ActionCatalog.Risk.valueOf(risk).label();
        } catch (IllegalArgumentException e) {
            return risk;
        }
    }

    static String outcomeLabel(JournalEntry e) {
        return e.ok() ? "Réussi" : "Échec ou refus";
    }

    static String details(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        return details.entrySet().stream().map(x -> x.getKey() + "=" + x.getValue()).collect(Collectors.joining(", "));
    }

    /** Cellule CSV : guillemets échappés et formules neutralisées (=, +, -, @ en tête). */
    static String cell(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        String s = v.replace("\r", " ").replace("\n", " ");
        if ("=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static String esc(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(v.length());
        for (char c : v.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String CSS = """
            :root { --ink:#1d2433; --mute:#5b6476; --line:#e3e6ec; --ko:#b42318; --ok:#2f7d4f; --card:#fff; }
            * { box-sizing: border-box; }
            body { margin:0; font:15px/1.55 "Segoe UI", system-ui, -apple-system, Arial, sans-serif; color:var(--ink); background:#f5f6f8; }
            main { max-width:920px; margin:32px auto; background:var(--card); padding:36px 44px; border:1px solid var(--line); border-radius:10px; }
            h1 { margin:4px 0 6px; font-size:26px; }
            .kicker { margin:0; text-transform:uppercase; letter-spacing:.08em; font-size:12px; color:var(--mute); }
            .meta { margin:0 0 28px; color:var(--mute); }
            .timeline { display:grid; gap:18px; }
            article { border:1px solid var(--line); border-radius:10px; padding:16px 18px; border-left:4px solid var(--ok); }
            article.ko { border-left-color:var(--ko); }
            article > header { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-bottom:6px; }
            time { color:var(--mute); font-size:13px; }
            .badge { font-size:11px; text-transform:uppercase; letter-spacing:.04em; padding:2px 8px; border-radius:999px; background:#eef1f5; color:var(--mute); }
            .badge.ok { background:#e6f4ea; color:var(--ok); }
            .badge.ko { background:#fce8e6; color:var(--ko); }
            h2 { margin:4px 0 10px; font-size:17px; font-weight:600; }
            ul.details { margin:0; padding-left:1.1rem; color:var(--ink); }
            ul.details li { margin:0.2rem 0; }
            footer { margin-top:28px; font-size:12px; color:var(--mute); text-align:center; }
            @media print { body { background:#fff; } main { margin:0; border:0; padding:0; max-width:none; } article { break-inside:avoid; } }
            """;
}
