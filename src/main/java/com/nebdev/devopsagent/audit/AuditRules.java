package com.nebdev.devopsagent.audit;

import com.nebdev.devopsagent.audit.AuditReport.Facts;
import com.nebdev.devopsagent.audit.AuditReport.Finding;
import com.nebdev.devopsagent.audit.AuditReport.Severity;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.BackupFile;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.Hygiene;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.ListeningPort;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Règles d’audit (domaine pur : ni SSH, ni HTTP). Une analyse → constats, points forts, note.
 * Formulation publique : aucun nom de projet personnel (ADR-022).
 */
public final class AuditRules {

    /** Fin du support standard (pas les extensions payantes type Ubuntu Pro / ESM). */
    static final Map<String, LocalDate> OS_END_OF_SUPPORT = new LinkedHashMap<>();
    /** Services qui ne doivent jamais écouter sur une adresse publique. */
    static final Map<Integer, Exposed> SENSITIVE_PORTS = new LinkedHashMap<>();
    /** Une sauvegarde plus petite est presque toujours un dump vide ou un message d’erreur chiffré. */
    static final long SUSPICIOUS_BACKUP_BYTES = 1024;
    /** Exercice mensuel + marge (minuteur décalé, VPS éteint le 1er). */
    static final long DRILL_MAX_AGE_DAYS = 40;
    private static final DateTimeFormatter DRILL_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)
            .withZone(ZoneOffset.UTC);

    record Exposed(String service, Severity severity) {}

    static {
        OS_END_OF_SUPPORT.put("ubuntu 16.04", LocalDate.of(2021, 4, 30));
        OS_END_OF_SUPPORT.put("ubuntu 18.04", LocalDate.of(2023, 5, 31));
        OS_END_OF_SUPPORT.put("ubuntu 20.04", LocalDate.of(2025, 5, 31));
        OS_END_OF_SUPPORT.put("ubuntu 22.04", LocalDate.of(2027, 6, 1));
        OS_END_OF_SUPPORT.put("ubuntu 24.04", LocalDate.of(2029, 5, 31));
        OS_END_OF_SUPPORT.put("ubuntu 26.04", LocalDate.of(2031, 5, 31));
        OS_END_OF_SUPPORT.put("debian 13", LocalDate.of(2030, 6, 30));
        OS_END_OF_SUPPORT.put("debian 9", LocalDate.of(2022, 6, 30));
        OS_END_OF_SUPPORT.put("debian 10", LocalDate.of(2024, 6, 30));
        OS_END_OF_SUPPORT.put("debian 11", LocalDate.of(2026, 8, 31));
        OS_END_OF_SUPPORT.put("debian 12", LocalDate.of(2028, 6, 30));

        SENSITIVE_PORTS.put(2375, new Exposed("API Docker sans chiffrement", Severity.CRITIQUE));
        SENSITIVE_PORTS.put(6379, new Exposed("Redis", Severity.CRITIQUE));
        SENSITIVE_PORTS.put(27017, new Exposed("MongoDB", Severity.CRITIQUE));
        SENSITIVE_PORTS.put(9200, new Exposed("Elasticsearch", Severity.CRITIQUE));
        SENSITIVE_PORTS.put(11211, new Exposed("Memcached", Severity.CRITIQUE));
        SENSITIVE_PORTS.put(3306, new Exposed("MySQL / MariaDB", Severity.ELEVE));
        SENSITIVE_PORTS.put(5432, new Exposed("PostgreSQL", Severity.ELEVE));
        SENSITIVE_PORTS.put(8080, new Exposed("serveur d’application (Tomcat, Node…)", Severity.MOYEN));
        SENSITIVE_PORTS.put(8081, new Exposed("serveur d’application (Tomcat, Node…)", Severity.MOYEN));
        SENSITIVE_PORTS.put(8009, new Exposed("connecteur AJP Tomcat", Severity.ELEVE));
    }

    private AuditRules() {}

    public static AuditReport evaluate(ServerSnapshot s) {
        Instant at = s.capturedAt() == null ? Instant.now() : s.capturedAt();
        List<Finding> findings = new ArrayList<>();
        List<String> strengths = new ArrayList<>();
        Hygiene h = s.hygiene();
        Set<String> signals = h == null || h.signals() == null ? Set.of() : new LinkedHashSet<>(h.signals());

        intrusion(signals, findings);
        access(s, findings, strengths);
        boolean firewallActive = firewall(s, findings, strengths);
        network(s, firewallActive, findings);
        updates(s, h, at, findings, strengths);
        backups(s, h, at, findings, strengths);
        resources(s, findings);
        architecture(s, signals, findings, strengths);

        findings.sort(Comparator.comparing(Finding::severity));
        int score = Math.max(0, 100 - findings.stream().mapToInt(f -> f.severity().penalty()).sum());
        String grade = grade(score, findings.stream().anyMatch(f -> f.severity() == Severity.CRITIQUE));
        return new AuditReport(
                s.host(),
                s.hostname(),
                at,
                blank(s.osPretty()) ? (s.osId() + " " + s.osVersion()).trim() : s.osPretty(),
                score,
                grade,
                List.copyOf(findings),
                List.copyOf(strengths),
                facts(s)
        );
    }

    static String grade(int score, boolean anyCritical) {
        String g = score >= 90 ? "A" : score >= 75 ? "B" : score >= 60 ? "C" : score >= 40 ? "D" : "E";
        if (anyCritical && g.compareTo("D") < 0) {
            return "D";
        }
        return g;
    }

    private static void intrusion(Set<String> signals, List<Finding> out) {
        if (signals.contains("MINER_PROCESS")) {
            out.add(new Finding("miner", Severity.CRITIQUE, "Intrusion",
                    "Programme de minage de cryptomonnaie en cours d’exécution",
                    "Un processus connu des logiciels de minage (xmrig, kinsing…) tourne sur le serveur.",
                    "Le serveur est très probablement compromis : un tiers l’utilise et peut lire les données.",
                    "Isoler le serveur, sauvegarder les données, réinstaller sur un système propre, changer tous les mots de passe."));
        }
    }

    private static void access(ServerSnapshot s, List<Finding> out, List<String> strengths) {
        if (s.ssh() == null) {
            return;
        }
        String pwd = lower(s.ssh().passwordAuthentication());
        String root = lower(s.ssh().permitRootLogin());
        if (pwd.equals("yes")) {
            out.add(new Finding("ssh-password", Severity.ELEVE, "Accès",
                    "Connexion SSH par mot de passe autorisée",
                    "Le serveur accepte les mots de passe pour l’accès administrateur à distance.",
                    "Des robots essaient en continu des mots de passe sur tous les serveurs d’Internet.",
                    "Passer aux clés SSH seulement, puis désactiver les mots de passe."));
        } else if (pwd.equals("no")) {
            strengths.add("Accès SSH par clé uniquement.");
        }
        if (root.equals("yes")) {
            out.add(new Finding("ssh-root", Severity.ELEVE, "Accès",
                    "Connexion directe du compte root autorisée",
                    "Le compte administrateur principal peut se connecter directement en SSH.",
                    "Une seule information devinée donne le contrôle total du serveur.",
                    "Interdire root en SSH ; se connecter avec un compte nominatif puis élever ses droits."));
        } else if (root.equals("no")) {
            strengths.add("Connexion directe de root interdite.");
        }
    }

    private static boolean firewall(ServerSnapshot s, List<Finding> out, List<String> strengths) {
        if (s.firewall() == null) {
            return false;
        }
        String status = lower(s.firewall().ufwStatus());
        boolean active = status.equals("active");
        if (active) {
            strengths.add("Pare-feu actif.");
        } else {
            out.add(new Finding("firewall", status.equals("inactive") ? Severity.ELEVE : Severity.MOYEN, "Réseau",
                    status.equals("inactive") ? "Pare-feu désactivé" : "Pare-feu non vérifiable",
                    status.equals("inactive")
                            ? "Aucun pare-feu ne filtre les connexions entrantes."
                            : "L’état du pare-feu n’a pas pu être lu.",
                    "Tout service qui écoute, même par erreur, est joignable depuis Internet.",
                    "Activer un pare-feu qui n’ouvre que SSH et le web (80, 443)."));
        }
        String f2b = lower(s.firewall().fail2banStatus());
        if (f2b.equals("active")) {
            strengths.add("Blocage automatique des attaques par force brute (fail2ban).");
        } else {
            out.add(new Finding("fail2ban", Severity.MOYEN, "Accès",
                    "Pas de blocage des tentatives de connexion répétées",
                    "fail2ban (ou équivalent) n’est pas actif.",
                    "Les tentatives de connexion répétées ne sont jamais bloquées.",
                    "Installer fail2ban sur SSH."));
        }
        return active;
    }

    private static void network(ServerSnapshot s, boolean firewallActive, List<Finding> out) {
        if (s.listeningPorts() == null) {
            return;
        }
        Map<Integer, Exposed> hits = new LinkedHashMap<>();
        for (ListeningPort p : s.listeningPorts()) {
            if (p.protocol() != null && p.protocol().startsWith("tcp") && isPublic(p.address())
                    && SENSITIVE_PORTS.containsKey(p.port())) {
                hits.putIfAbsent(p.port(), SENSITIVE_PORTS.get(p.port()));
            }
        }
        for (Map.Entry<Integer, Exposed> e : hits.entrySet()) {
            Severity sev = firewallActive ? e.getValue().severity().lower() : e.getValue().severity();
            out.add(new Finding("port-" + e.getKey(), sev, "Réseau",
                    e.getValue().service() + " écoute sur toutes les adresses (port " + e.getKey() + ")",
                    "Le service écoute sur l’adresse publique du serveur."
                            + (firewallActive
                            ? " Le pare-feu peut le bloquer, mais les ports publiés par Docker le contournent."
                            : " Aucun pare-feu actif."),
                    "Accès direct aux données depuis Internet ; ces services sont une cible courante des attaques automatiques.",
                    "Faire écouter le service sur 127.0.0.1 ou un réseau privé, et le fermer au pare-feu."));
        }
    }

    private static void updates(ServerSnapshot s, Hygiene h, Instant at, List<Finding> out, List<String> strengths) {
        String key = (lower(s.osId()) + " " + nullToEmpty(s.osVersion())).trim();
        LocalDate eos = OS_END_OF_SUPPORT.get(key);
        LocalDate today = LocalDate.ofInstant(at, ZoneOffset.UTC);
        if (eos != null && today.isAfter(eos)) {
            out.add(new Finding("os-eol", Severity.ELEVE, "Mises à jour",
                    "Système d’exploitation en fin de support (" + s.osPretty() + ")",
                    "Le support standard s’est terminé le " + eos + ".",
                    "Les nouvelles failles de sécurité ne sont plus corrigées.",
                    "Planifier la migration vers une version supportée."));
        } else if (eos != null && today.plusMonths(9).isAfter(eos)) {
            out.add(new Finding("os-eol-soon", Severity.FAIBLE, "Mises à jour",
                    "Fin de support du système proche",
                    "Le support standard se termine le " + eos + ".",
                    "Sans migration, le serveur ne recevra plus de correctifs.",
                    "Prévoir la migration dans les prochains mois."));
        }
        if (h == null) {
            return;
        }
        if (h.securityUpdates() != null && h.securityUpdates() > 0) {
            out.add(new Finding("security-updates", h.securityUpdates() >= 20 ? Severity.ELEVE : Severity.MOYEN,
                    "Mises à jour",
                    h.securityUpdates() + " correctif(s) de sécurité non installé(s)",
                    h.securityUpdates() + " mise(s) à jour de sécurité sur " + h.pendingUpdates() + " en attente.",
                    "Des failles connues et publiées restent ouvertes.",
                    "Installer les mises à jour, puis activer leur installation automatique."));
        } else if (h.pendingUpdates() != null && h.pendingUpdates() > 0) {
            out.add(new Finding("updates", Severity.FAIBLE, "Mises à jour",
                    h.pendingUpdates() + " mise(s) à jour en attente",
                    "Aucune n’est marquée sécurité.",
                    "Écart croissant avec les versions corrigées.",
                    "Installer les mises à jour lors d’une fenêtre de maintenance."));
        }
        if (Boolean.TRUE.equals(h.rebootRequired())) {
            out.add(new Finding("reboot", Severity.MOYEN, "Mises à jour",
                    "Redémarrage requis",
                    "Des correctifs (souvent du noyau) sont installés mais pas encore actifs.",
                    "Le serveur tourne encore avec les versions vulnérables.",
                    "Redémarrer le serveur lors d’une fenêtre de maintenance."));
        }
        if (Boolean.FALSE.equals(h.autoUpdates())) {
            out.add(new Finding("auto-updates", Severity.MOYEN, "Mises à jour",
                    "Mises à jour de sécurité automatiques désactivées",
                    "Les correctifs ne s’installent que si quelqu’un intervient.",
                    "Les failles restent ouvertes entre deux interventions.",
                    "Activer unattended-upgrades pour les correctifs de sécurité."));
        } else if (Boolean.TRUE.equals(h.autoUpdates())) {
            strengths.add("Correctifs de sécurité installés automatiquement.");
        }
    }

    private static void backups(ServerSnapshot s, Hygiene h, Instant at, List<Finding> out, List<String> strengths) {
        if (h == null) {
            return;
        }
        boolean hasDb = s.mariadb() != null && s.mariadb().present()
                || (h.signals() != null && h.signals().contains("MARIADB_HOST"));
        List<BackupFile> files = h.backupFiles() == null ? List.of() : h.backupFiles();
        if (files.isEmpty()) {
            out.add(new Finding("no-backup", hasDb ? Severity.ELEVE : Severity.MOYEN, "Sauvegardes",
                    "Aucune sauvegarde trouvée sur le serveur",
                    "Aucune sauvegarde chiffrée n’a été trouvée à l’emplacement standard."
                            + " Si une autre solution existe, elle doit être documentée et testée.",
                    "Une panne, une erreur ou une attaque peut faire perdre définitivement les données.",
                    "Mettre en place une sauvegarde quotidienne chiffrée, copiée hors du serveur, et tester sa restauration."));
            return;
        }
        // Les archives de fichiers (files-*) peuvent être petites : la taille et l’âge se jugent sur les dumps.
        List<BackupFile> dumps = files.stream().filter(f -> !f.name().startsWith("files-")).toList();
        BackupFile latest = (dumps.isEmpty() ? files : dumps).stream()
                .max(Comparator.comparingLong(BackupFile::modifiedEpochSeconds)).orElseThrow();
        long ageHours = Duration.between(Instant.ofEpochSecond(latest.modifiedEpochSeconds()), at).toHours();
        boolean suspicious = latest.sizeBytes() < SUSPICIOUS_BACKUP_BYTES;
        if (suspicious) {
            out.add(new Finding("backup-empty", Severity.ELEVE, "Sauvegardes",
                    "Dernière sauvegarde anormalement petite (" + latest.sizeBytes() + " octets)",
                    "La sauvegarde la plus récente ne peut pas contenir une base de données réelle.",
                    "La sauvegarde existe mais ne permettrait pas de restaurer les données.",
                    "Vérifier le contenu de la sauvegarde et refaire une restauration d’essai."));
        }
        if (ageHours > 48) {
            out.add(new Finding("backup-old", Severity.MOYEN, "Sauvegardes",
                    "Dernière sauvegarde vieille de " + (ageHours / 24) + " jour(s)",
                    "Aucune sauvegarde récente.",
                    "Tout ce qui a été saisi depuis serait perdu en cas d’incident.",
                    "Programmer une sauvegarde quotidienne."));
        }
        if (Boolean.FALSE.equals(h.backupTimer())) {
            out.add(new Finding("backup-schedule", Severity.MOYEN, "Sauvegardes",
                    "Sauvegardes non programmées",
                    "Les sauvegardes ne sont faites qu’à la main.",
                    "Elles finissent par être oubliées.",
                    "Programmer une sauvegarde automatique quotidienne."));
        }
        if (!suspicious && ageHours <= 48) {
            strengths.add("Sauvegarde chiffrée de moins de 48 h présente.");
        }
        restoreDrill(h, at, out, strengths);
    }

    /** Exercice de restauration automatique (BIZ-12). Rien n’est dit si l’analyse ne sait pas encore le lire. */
    static void restoreDrill(Hygiene h, Instant at, List<Finding> out, List<String> strengths) {
        if (h.drillTimer() == null) {
            return;
        }
        ServerSnapshot.AutoDrill d = h.autoDrill();
        Instant when = null;
        if (d != null) {
            try {
                when = Instant.parse(d.at());
            } catch (DateTimeParseException ignored) {
                // date illisible : traité comme absent
            }
        }
        if (d != null && when != null && !d.ok()) {
            out.add(new Finding("restore-drill-failed", Severity.ELEVE, "Sauvegardes",
                    "Le dernier exercice de restauration a échoué",
                    "Exercice automatique du " + DRILL_DATE.format(when) + " : " + String.join(" · ", d.lines()) + ".",
                    "La sauvegarde existe peut-être, mais rien ne prouve qu’elle se restaure.",
                    "Relancer l’exercice depuis la console, lire l’écart, corriger la sauvegarde avant tout incident."));
            return;
        }
        long days = when == null ? Long.MAX_VALUE : Duration.between(when, at).toDays();
        if (days > DRILL_MAX_AGE_DAYS) {
            out.add(new Finding("restore-drill-missing", Severity.FAIBLE, "Sauvegardes",
                    when == null ? "Restauration jamais testée" : "Dernier exercice de restauration vieux de " + days + " jours",
                    "Aucun exercice de restauration récent n’a été trouvé sur le serveur.",
                    "Une sauvegarde jamais restaurée peut se révéler inutilisable le jour où on en a besoin.",
                    "Programmer un exercice de restauration mensuel (base et fichiers restaurés à part, puis comptés)."));
            return;
        }
        strengths.add("Dernière sauvegarde restaurée avec succès le " + DRILL_DATE.format(when) + " (exercice automatique).");
    }

    private static void resources(ServerSnapshot s, List<Finding> out) {
        if (s.disk() != null && s.disk().totalBytes() > 0) {
            int pct = (int) Math.round(100.0 * s.disk().usedBytes() / s.disk().totalBytes());
            if (pct >= 80) {
                out.add(new Finding("disk", pct >= 90 ? Severity.ELEVE : Severity.MOYEN, "Ressources",
                        "Disque rempli à " + pct + " %",
                        "Espace libre faible sur la partition système.",
                        "Disque plein = base de données bloquée, sauvegardes et mises à jour en échec.",
                        "Nettoyer les journaux et images inutiles, ou agrandir le disque."));
            }
        }
        if (s.memory() != null && s.memory().totalBytes() > 0) {
            double free = 1.0 * s.memory().availableBytes() / s.memory().totalBytes();
            if (free < 0.10) {
                out.add(new Finding("memory", Severity.MOYEN, "Ressources",
                        "Mémoire disponible sous 10 %",
                        "Le serveur est proche de sa limite de mémoire.",
                        "Risque d’arrêt brutal d’une application ou de la base de données.",
                        "Identifier le service gourmand ou augmenter la mémoire."));
            }
        }
    }

    private static void architecture(ServerSnapshot s, Set<String> signals, List<Finding> out, List<String> strengths) {
        List<String> host = new ArrayList<>();
        if (signals.contains("APACHE_PRESENT")) {
            host.add("Apache");
        }
        if (signals.contains("NGINX_HOST_PRESENT") || (s.nginx() != null && s.nginx().present())) {
            host.add("Nginx");
        }
        if (signals.contains("PHP_PRESENT")) {
            host.add("PHP");
        }
        if (signals.contains("MARIADB_HOST")) {
            host.add("MySQL/MariaDB");
        }
        if (!host.isEmpty()) {
            out.add(new Finding("host-stack", Severity.FAIBLE, "Architecture",
                    "Applications installées directement sur le système (" + String.join(", ", host) + ")",
                    "Les logiciels applicatifs sont installés sur l’hôte, sans isolation.",
                    "Une faille dans une application expose tout le serveur ; mises à jour et migrations sont manuelles.",
                    "Isoler chaque application dans un conteneur derrière un proxy unique avec HTTPS automatique."));
        }
        boolean docker = s.docker() != null && s.docker().present();
        boolean traefik = s.traefik() != null && s.traefik().present();
        if (docker && traefik) {
            strengths.add("Applications isolées en conteneurs derrière un proxy unique.");
        }
    }

    private static Facts facts(ServerSnapshot s) {
        String cpu = s.cpu() == null ? "—" : s.cpu().vcpus() + " vCPU";
        String mem = s.memory() == null ? "—" : gb(s.memory().totalBytes()) + " (" + gb(s.memory().availableBytes()) + " disponibles)";
        String disk = s.disk() == null || s.disk().totalBytes() == 0 ? "—"
                : gb(s.disk().totalBytes()) + " (" + Math.round(100.0 * s.disk().usedBytes() / s.disk().totalBytes()) + " % utilisés)";
        Set<Integer> pub = new java.util.TreeSet<>();
        if (s.listeningPorts() != null) {
            for (ListeningPort p : s.listeningPorts()) {
                if (p.protocol() != null && p.protocol().startsWith("tcp") && isPublic(p.address())) {
                    pub.add(p.port());
                }
            }
        }
        String fw = s.firewall() == null ? "—"
                : nullToDash(s.firewall().ufwStatus()) + " · fail2ban " + nullToDash(s.firewall().fail2banStatus());
        String ssh = s.ssh() == null ? "—"
                : "mot de passe " + nullToDash(s.ssh().passwordAuthentication()) + " · root " + nullToDash(s.ssh().permitRootLogin());
        String containers = (s.docker() != null && s.docker().present() ? "Docker" : "pas de Docker")
                + (s.traefik() != null && s.traefik().present() ? " · Traefik" : "")
                + (s.mariadb() != null && s.mariadb().present() ? " · MariaDB" : "")
                + (s.redis() != null && s.redis().present() ? " · Redis" : "");
        return new Facts(cpu, mem, disk, pub.isEmpty() ? "—" : pub.toString().replaceAll("[\\[\\]]", ""), fw, ssh, containers);
    }

    static boolean isPublic(String address) {
        if (address == null) {
            return false;
        }
        String a = address.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
        int pct = a.indexOf('%');
        if (pct >= 0) {
            a = a.substring(0, pct);
        }
        if (a.equals("0.0.0.0") || a.equals("*") || a.equals("::")) {
            return true;
        }
        if (a.startsWith("127.") || a.equals("::1") || a.equals("localhost") || a.startsWith("fe80")
                || a.startsWith("10.") || a.startsWith("192.168.") || a.startsWith("fd") || a.startsWith("fc")) {
            return false;
        }
        if (a.startsWith("172.")) {
            String[] parts = a.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second < 16 || second > 31;
                } catch (NumberFormatException e) {
                    return true;
                }
            }
        }
        return true;
    }

    private static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.1f Go", bytes / (1024.0 * 1024 * 1024));
    }

    private static String lower(String v) {
        return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    private static String nullToDash(String v) {
        return v == null || v.isBlank() ? "—" : v;
    }
}
