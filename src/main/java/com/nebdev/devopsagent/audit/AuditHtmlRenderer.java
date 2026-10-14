package com.nebdev.devopsagent.audit;

import com.nebdev.devopsagent.audit.AuditReport.Finding;
import com.nebdev.devopsagent.audit.AuditReport.Severity;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Rapport client autonome (une page HTML, styles inclus, imprimable en PDF depuis le navigateur).
 */
public final class AuditHtmlRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRENCH)
            .withZone(ZoneId.of("UTC"));

    private AuditHtmlRenderer() {}

    /** @param client nom du client affiché en tête ; vide = non affiché */
    public static String render(AuditReport r, String client) {
        StringBuilder b = new StringBuilder(16_000);
        b.append("<!DOCTYPE html>\n<html lang=\"fr\">\n<head>\n<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Audit serveur — ").append(esc(r.host())).append("</title>\n<style>\n")
                .append(CSS).append("</style>\n</head>\n<body>\n<main>\n");

        b.append("<header>\n<p class=\"kicker\">Rapport d’audit serveur</p>\n<h1>")
                .append(esc(r.hostname() == null || r.hostname().isBlank() ? r.host() : r.hostname()))
                .append("</h1>\n<p class=\"meta\">");
        if (client != null && !client.isBlank()) {
            b.append("Préparé pour <strong>").append(esc(client.strip())).append("</strong> · ");
        }
        b.append("Adresse ").append(esc(r.host())).append(" · ").append(esc(r.os()))
                .append(" · Analyse du ").append(esc(DATE.format(r.capturedAt()))).append(" UTC</p>\n")
                .append("<p class=\"note\">Analyse en lecture seule : aucune modification n’a été faite sur le serveur.</p>\n")
                .append("</header>\n");

        b.append("<section class=\"summary\">\n<div class=\"grade grade-").append(esc(r.grade())).append("\">")
                .append(esc(r.grade())).append("<span>").append(r.score()).append(" / 100</span></div>\n<div>\n")
                .append("<h2>Synthèse</h2>\n<p>").append(esc(verdict(r))).append("</p>\n<ul class=\"counts\">");
        for (Severity s : Severity.values()) {
            b.append("<li class=\"sev-").append(s.name().toLowerCase(Locale.ROOT)).append("\"><strong>")
                    .append(r.count(s)).append("</strong> ").append(esc(s.label())).append("</li>");
        }
        b.append("</ul>\n</div>\n</section>\n");

        if (!r.strengths().isEmpty()) {
            b.append("<section>\n<h2>Points forts</h2>\n<ul class=\"strengths\">\n");
            for (String s : r.strengths()) {
                b.append("<li>").append(esc(s)).append("</li>\n");
            }
            b.append("</ul>\n</section>\n");
        }

        b.append("<section>\n<h2>Constats et recommandations</h2>\n");
        if (r.findings().isEmpty()) {
            b.append("<p>Aucun constat. Le serveur respecte toutes les règles vérifiées.</p>\n");
        }
        for (Finding f : r.findings()) {
            b.append("<article class=\"finding sev-").append(f.severity().name().toLowerCase(Locale.ROOT)).append("\">\n")
                    .append("<div class=\"tag\">").append(esc(f.severity().label())).append(" · ")
                    .append(esc(f.category())).append("</div>\n<h3>").append(esc(f.title())).append("</h3>\n")
                    .append("<dl>\n<dt>Constat</dt><dd>").append(esc(f.observed())).append("</dd>\n")
                    .append("<dt>Risque</dt><dd>").append(esc(f.risk())).append("</dd>\n")
                    .append("<dt>Recommandation</dt><dd>").append(esc(f.recommendation())).append("</dd>\n</dl>\n")
                    .append("</article>\n");
        }
        b.append("</section>\n");

        AuditReport.Facts x = r.facts();
        b.append("<section>\n<h2>Fiche technique</h2>\n<table>\n")
                .append(row("Processeur", x.cpu()))
                .append(row("Mémoire", x.memory()))
                .append(row("Disque système", x.disk()))
                .append(row("Ports ouverts sur l’adresse publique", x.publicPorts()))
                .append(row("Pare-feu", x.firewall()))
                .append(row("SSH", x.ssh()))
                .append(row("Conteneurs", x.containers()))
                .append("</table>\n</section>\n");

        b.append("<section class=\"method\">\n<h2>Méthode</h2>\n<p>Lecture de la configuration du système, des ports en écoute, ")
                .append("du pare-feu, de SSH, des mises à jour en attente et des sauvegardes présentes. Note sur 100 : ")
                .append("−30 par constat critique, −15 élevé, −7 moyen, −2 faible ; un constat critique plafonne la note à D. ")
                .append("Un audit automatique ne remplace pas un test d’intrusion ; il repère les écarts les plus fréquents.</p>\n")
                .append("</section>\n");

        b.append("<footer>Rapport généré par VPS Autopilot.</footer>\n</main>\n</body>\n</html>\n");
        return b.toString();
    }

    static String verdict(AuditReport r) {
        long crit = r.count(Severity.CRITIQUE);
        long high = r.count(Severity.ELEVE);
        if (crit > 0) {
            return crit + " constat(s) critique(s) demandent une action immédiate.";
        }
        if (high > 0) {
            return high + " risque(s) élevé(s) à corriger rapidement.";
        }
        if (!r.findings().isEmpty()) {
            return "Pas de risque majeur ; quelques améliorations recommandées.";
        }
        return "Aucun écart détecté sur les points vérifiés.";
    }

    private static String row(String label, String value) {
        return "<tr><th>" + esc(label) + "</th><td>" + esc(value) + "</td></tr>\n";
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
            :root { --ink:#1d2433; --mute:#5b6476; --line:#e3e6ec; --crit:#b42318; --high:#c4520f; --mid:#a07a00; --low:#4a6c8c; --ok:#2f7d4f; }
            * { box-sizing: border-box; }
            body { margin:0; font:15px/1.55 "Segoe UI", system-ui, -apple-system, Arial, sans-serif; color:var(--ink); background:#f5f6f8; }
            main { max-width:860px; margin:32px auto; background:#fff; padding:40px 48px; border:1px solid var(--line); border-radius:10px; }
            h1 { margin:4px 0 6px; font-size:28px; } h2 { font-size:18px; margin:28px 0 10px; } h3 { font-size:16px; margin:4px 0 8px; }
            .kicker { margin:0; text-transform:uppercase; letter-spacing:.08em; font-size:12px; color:var(--mute); }
            .meta { margin:0; color:var(--mute); } .note { margin:10px 0 0; font-size:13px; color:var(--ok); }
            .summary { display:flex; gap:28px; align-items:center; margin-top:24px; padding:20px; border:1px solid var(--line); border-radius:10px; }
            .summary h2 { margin-top:0; }
            .grade { flex:0 0 120px; height:120px; border-radius:12px; display:flex; flex-direction:column; align-items:center; justify-content:center; font-size:52px; font-weight:700; color:#fff; }
            .grade span { font-size:13px; font-weight:500; opacity:.9; }
            .grade-A { background:var(--ok); } .grade-B { background:#5a8f3a; } .grade-C { background:var(--mid); } .grade-D { background:var(--high); } .grade-E { background:var(--crit); }
            .counts { list-style:none; padding:0; margin:8px 0 0; display:flex; gap:16px; flex-wrap:wrap; font-size:14px; }
            .strengths li { margin:2px 0; color:var(--ok); }
            .finding { border:1px solid var(--line); border-left:5px solid var(--low); border-radius:8px; padding:14px 18px; margin:12px 0; break-inside:avoid; }
            .finding.sev-critique { border-left-color:var(--crit); } .finding.sev-eleve { border-left-color:var(--high); } .finding.sev-moyen { border-left-color:var(--mid); }
            .tag { font-size:12px; text-transform:uppercase; letter-spacing:.06em; color:var(--mute); }
            .sev-critique .tag, li.sev-critique strong { color:var(--crit); } .sev-eleve .tag, li.sev-eleve strong { color:var(--high); }
            .sev-moyen .tag, li.sev-moyen strong { color:var(--mid); } li.sev-faible strong { color:var(--low); }
            dl { margin:0; display:grid; grid-template-columns:130px 1fr; gap:4px 12px; } dt { color:var(--mute); } dd { margin:0; }
            table { width:100%; border-collapse:collapse; } th, td { text-align:left; padding:7px 10px; border-bottom:1px solid var(--line); vertical-align:top; }
            th { width:40%; color:var(--mute); font-weight:500; }
            .method p { font-size:13px; color:var(--mute); }
            footer { margin-top:28px; font-size:12px; color:var(--mute); text-align:center; }
            @media print { body { background:#fff; } main { margin:0; border:0; padding:0; max-width:none; } }
            """;
}
