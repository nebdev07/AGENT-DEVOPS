package com.nebdev.devopsagent.saas.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Conseiller déterministe (toujours disponible, sans réseau) : traduit capacités absentes et constats d'audit en
 * recommandations liées aux opérations du catalogue. Sert de repli quand aucun modèle n'est configuré ou répond mal.
 */
@Component
public class RulesAdvisor implements AiProvider {

    @Override
    public String id() {
        return "rules";
    }

    @Override
    public String model() {
        return "deterministic-v1";
    }

    @Override
    public Advice advise(Context ctx) {
        Map<String, Object> caps = ctx.facts();
        boolean fr = "fr".equals(ctx.locale());
        List<Recommendation> out = new ArrayList<>();
        add(out, caps, "security", "INSTALL_SECURITY", fr ? "Durcir l'accès SSH et le pare-feu" : "Harden SSH access and firewall",
                fr ? "Le socle de sécurité (UFW, fail2ban, SSH par clé) est incomplet." : "The security baseline (UFW, fail2ban, key-only SSH) is incomplete.", "HIGH");
        add(out, caps, "backups", "INSTALL_BACKUPS", fr ? "Mettre en place des sauvegardes chiffrées" : "Set up encrypted backups",
                fr ? "Aucune sauvegarde vérifiée n'est en place." : "No verified backup is in place.", "HIGH");
        add(out, caps, "docker", "INSTALL_DOCKER", fr ? "Installer Docker" : "Install Docker",
                fr ? "Les applications s'exécutent isolées dans des conteneurs." : "Applications run isolated in containers.", "MEDIUM");
        add(out, caps, "traefik", "INSTALL_TRAEFIK", fr ? "Installer Traefik et HTTPS" : "Install Traefik and HTTPS",
                fr ? "Point d'entrée unique, certificats automatiques." : "Single entry point with automatic certificates.", "MEDIUM");
        for (Map<String, String> f : ctx.findings()) {
            if (out.size() >= 8) {
                break;
            }
            String sev = f.getOrDefault("severity", "");
            if (sev.equals("CRITIQUE") || sev.equals("ELEVE") || sev.equals("CRITICAL") || sev.equals("HIGH")) {
                out.add(new Recommendation(trim(f.get("title")), trim(f.get("recommendation")), "HIGH", null));
            }
        }
        return new Advice(out, fr ? "Recommandations issues des règles d'audit." : "Recommendations from audit rules.");
    }

    private static void add(List<Recommendation> out, Map<String, Object> caps, String cap, String op, String title,
                            String why, String risk) {
        if (!Boolean.TRUE.equals(caps.get(cap))) {
            out.add(new Recommendation(title, why, risk, op));
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
