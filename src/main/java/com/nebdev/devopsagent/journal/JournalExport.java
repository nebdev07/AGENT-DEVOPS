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
        b.append("Date (UTC);Opérateur;Serveur;Utilisateur SSH;Action;Type;Résultat;Code;Durée (ms);Détails;Erreur\r\n");
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
                    cell(String.valueOf(e.durationMs())),
                    cell(details(e.details())),
                    cell(e.error())
            )).append("\r\n");
        }
        return b.toString();
    }

    public static String html(String host, String client, List<JournalEntry> entries) {
        String title = host == null || host.isBlank() ? "Réglages du poste" : host.strip();
        StringBuilder b = new StringBuilder(8_000);
        b.append("<!DOCTYPE html>\n<html lang=\"fr\">\n<head>\n<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Journal des interventions — ").append(esc(title)).append("</title>\n<style>\n")
                .append(CSS).append("</style>\n</head>\n<body>\n<main>\n<header>\n")
                .append("<p class=\"kicker\">Journal des interventions</p>\n<h1>").append(esc(title)).append("</h1>\n<p class=\"meta\">");
        if (client != null && !client.isBlank()) {
            b.append("Préparé pour <strong>").append(esc(client.strip())).append("</strong> · ");
        }
        long changes = entries.stream().filter(e -> ActionCatalog.Risk.CHANGE.name().equals(e.risk())).count();
        long failed = entries.stream().filter(e -> !e.ok()).count();
        b.append(entries.size()).append(" action(s), dont ").append(changes).append(" modification(s) du serveur et ")
                .append(failed).append(" échec(s) ou refus. Heures en UTC.</p>\n</header>\n");
        if (entries.isEmpty()) {
            b.append("<p>Aucune action enregistrée.</p>\n");
        } else {
            b.append("<table>\n<thead><tr><th>Date</th><th>Action</th><th>Type</th><th>Résultat</th><th>Par</th><th>Détails</th></tr></thead>\n<tbody>\n");
            for (JournalEntry e : entries) {
                b.append("<tr class=\"").append(e.ok() ? "ok" : "ko").append("\"><td>").append(esc(date(e.at())))
                        .append("</td><td>").append(esc(e.action()))
                        .append("</td><td>").append(esc(riskLabel(e.risk())))
                        .append("</td><td>").append(esc(outcomeLabel(e)));
                if (e.error() != null && !e.error().isBlank()) {
                    b.append("<div class=\"err\">").append(esc(e.error())).append("</div>");
                }
                b.append("</td><td>").append(esc(e.operator()));
                if (e.user() != null && !e.user().isBlank()) {
                    b.append("<div class=\"mute\">SSH : ").append(esc(e.user())).append("</div>");
                }
                b.append("</td><td>").append(esc(details(e.details()))).append("</td></tr>\n");
            }
            b.append("</tbody>\n</table>\n");
        }
        b.append("<footer>Journal tenu automatiquement par VPS Autopilot : chaque action lancée depuis la console est ")
                .append("enregistrée avec son résultat. Les requêtes SQL et les mots de passe ne sont jamais recopiés.</footer>\n")
                .append("</main>\n</body>\n</html>\n");
        return b.toString();
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
            :root { --ink:#1d2433; --mute:#5b6476; --line:#e3e6ec; --ko:#b42318; --ok:#2f7d4f; }
            * { box-sizing: border-box; }
            body { margin:0; font:14px/1.5 "Segoe UI", system-ui, -apple-system, Arial, sans-serif; color:var(--ink); background:#f5f6f8; }
            main { max-width:1100px; margin:32px auto; background:#fff; padding:36px 44px; border:1px solid var(--line); border-radius:10px; }
            h1 { margin:4px 0 6px; font-size:26px; }
            .kicker { margin:0; text-transform:uppercase; letter-spacing:.08em; font-size:12px; color:var(--mute); }
            .meta { margin:0 0 20px; color:var(--mute); }
            table { width:100%; border-collapse:collapse; } th, td { text-align:left; padding:7px 8px; border-bottom:1px solid var(--line); vertical-align:top; }
            th { color:var(--mute); font-weight:500; font-size:12px; text-transform:uppercase; letter-spacing:.04em; }
            td:first-child { white-space:nowrap; }
            tr.ko td:nth-child(4) { color:var(--ko); } tr.ok td:nth-child(4) { color:var(--ok); }
            .err { font-size:12px; color:var(--ko); } .mute { font-size:12px; color:var(--mute); }
            footer { margin-top:24px; font-size:12px; color:var(--mute); text-align:center; }
            @media print { body { background:#fff; } main { margin:0; border:0; padding:0; max-width:none; } }
            """;
}
