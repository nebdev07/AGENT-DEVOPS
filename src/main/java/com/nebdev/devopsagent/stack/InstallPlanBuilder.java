package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.discovery.model.ServerSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class InstallPlanBuilder {

    static final Set<String> EXECUTABLE = Set.of("security", "docker", "traefik", "mariadb", "deploy");

    private InstallPlanBuilder() {}

    public static InstallPlan build(ServerSnapshot snapshot, Set<String> wanted) {
        return build(snapshot, wanted, Map.of());
    }

    public static InstallPlan build(
            ServerSnapshot snapshot,
            Set<String> wanted,
            Map<String, List<String>> details
    ) {
        List<InstallPlan.InstallAction> actions = new ArrayList<>();
        Set<String> enabled = wanted == null ? Set.of() : wanted;
        Map<String, List<String>> chosen = details == null ? Map.of() : details;
        for (StackComponent c : StackCatalog.all()) {
            if (!enabled.contains(c.id())) {
                continue;
            }
            actions.add(actionFor(c, snapshot, chosen.getOrDefault(c.id(), List.of())));
        }
        String host = snapshot == null ? "" : snapshot.host();
        String source = snapshot == null ? "" : snapshot.source();
        return new InstallPlan(host, source, List.copyOf(actions));
    }

    private static InstallPlan.InstallAction actionFor(StackComponent c, ServerSnapshot snap, List<String> selectedDetails) {
        if ("nginx".equals(c.id())) {
            return new InstallPlan.InstallAction(
                    c.id(), c.label(), c.phase(), InstallPlan.Status.REFUSED, "CHANGE",
                    "Refusé : Nginx sur l’hôte. Utilise Traefik + conteneurs.",
                    List.of()
            );
        }
        if (present(c.id(), snap, selectedDetails)) {
            String skipReason = "security".equals(c.id())
                    ? "Baseline complet : UFW actif, fail2ban, SSH clés seulement (pas de mot de passe, root fermé)."
                    : "Déjà présent. Rien à installer.";
            return new InstallPlan.InstallAction(
                    c.id(), c.label(), c.phase(), InstallPlan.Status.SKIP, "READ",
                    skipReason,
                    List.of()
            );
        }
        if (snap == null) {
            return new InstallPlan.InstallAction(
                    c.id(), c.label(), c.phase(), InstallPlan.Status.PLANNED, "CHANGE",
                    "Analyse d’abord le VPS.",
                    steps(c.id(), selectedDetails)
            );
        }
        if (!EXECUTABLE.contains(c.id())) {
            return new InstallPlan.InstallAction(
                    c.id(), c.label(), c.phase(), InstallPlan.Status.PLANNED, "CHANGE",
                    "Prévu en " + c.phase() + ". Le playbook n’est pas encore exécutable.",
                    steps(c.id(), selectedDetails)
            );
        }
        if (c.requires().contains("docker") && !present("docker", snap, List.of())) {
            return new InstallPlan.InstallAction(
                    c.id(), c.label(), c.phase(), InstallPlan.Status.PLANNED, "CHANGE",
                    "Installer Docker d’abord.",
                    steps(c.id(), selectedDetails)
            );
        }
        if (("traefik".equals(c.id()) || "mariadb".equals(c.id()) || "deploy".equals(c.id())) && !securityPresent(snap, List.of())) {
            return new InstallPlan.InstallAction(
                    c.id(), c.label(), c.phase(), InstallPlan.Status.PLANNED, "CHANGE",
                    "Baseline sécurité incomplet. Pas de pile applicative tant que Sécurité n’est pas SKIP.",
                    steps(c.id(), selectedDetails)
            );
        }
        if (snap != null && snap.source() != null && snap.source().startsWith("fixture")) {
            return new InstallPlan.InstallAction(
                    c.id(), c.label(), c.phase(), InstallPlan.Status.PLANNED, "CHANGE",
                    "Mode laboratoire : pas d’installation réelle. Analyse le vrai VPS.",
                    steps(c.id(), selectedDetails)
            );
        }
        String readyReason = switch (c.id()) {
            case "security" -> securityReadyReason(snap, selectedDetails);
            case "traefik" -> "Traefik dans Docker, réseau proxy. UFW 80/443. Aucune app exposée tant qu’un routeur n’est pas déclaré.";
            case "mariadb" -> "Conteneur MariaDB, réseau interne data, pas de port public. Mot de passe root hors git sur le VPS.";
            case "deploy" -> "Compte deploy : docker, pas sudo, clé de l’agent, SSH autorisé. Aucune app lancée.";
            case "docker" -> dockerReadyReason(snap, selectedDetails);
            default -> "Absent. Install idempotente, versionnée, après confirmation.";
        };
        return new InstallPlan.InstallAction(
                c.id(), c.label(), c.phase(), InstallPlan.Status.READY, "CHANGE",
                readyReason,
                steps(c.id(), selectedDetails)
        );
    }

    static boolean present(String id, ServerSnapshot snap) {
        return present(id, snap, List.of());
    }

    static boolean dockerPresent(ServerSnapshot snap, List<String> selectedDetails) {
        if (snap == null || snap.docker() == null || !snap.docker().present()) {
            return false;
        }
        return selectedDetails == null || selectedDetails.isEmpty();
    }

    static String dockerReadyReason(ServerSnapshot snap, List<String> selectedDetails) {
        if (snap != null && snap.docker() != null && snap.docker().present()) {
            return "Moteur déjà là. Il reste des détails cochés (Compose, groupe docker). "
                    + "Installer est idempotent et ne réinstalle pas le moteur.";
        }
        return "Absent. Install idempotente, versionnée, après confirmation.";
    }

    static boolean present(String id, ServerSnapshot snap, List<String> selectedDetails) {
        if (snap == null) {
            return false;
        }
        return switch (id) {
            case "docker" -> dockerPresent(snap, selectedDetails);
            case "traefik" -> snap.traefik() != null && snap.traefik().present();
            case "mariadb" -> snap.mariadb() != null && snap.mariadb().present();
            case "redis" -> snap.redis() != null && snap.redis().present();
            case "nginx" -> snap.nginx() != null && snap.nginx().present();
            case "security" -> securityPresent(snap, selectedDetails);
            case "deploy" -> snap.deploy() != null && snap.deploy().present();
            case "backups" -> false;
            default -> false;
        };
    }

    static boolean securityPresent(ServerSnapshot snap) {
        return securityPresent(snap, List.of());
    }

    static boolean securityPresent(ServerSnapshot snap, List<String> selectedDetails) {
        return securityGaps(snap, selectedDetails).isEmpty();
    }

    static List<String> securityGaps(ServerSnapshot snap) {
        return securityGaps(snap, List.of());
    }

    static List<String> securityGaps(ServerSnapshot snap, List<String> selectedDetails) {
        List<String> gaps = new ArrayList<>();
        if (snap == null) {
            gaps.add("pas d’analyse");
            return gaps;
        }
        boolean ufwOn = false;
        boolean fail2banOn = false;
        if (snap.firewall() != null) {
            String ufw = String.valueOf(snap.firewall().ufwStatus()).toLowerCase(Locale.ROOT);
            ufwOn = ufw.equals("active") || ufw.equals("enabled");
            String f2b = String.valueOf(snap.firewall().fail2banStatus()).toLowerCase(Locale.ROOT);
            fail2banOn = f2b.equals("active") || f2b.equals("installed");
        }
        if (!fail2banOn && snap.runningServices() != null) {
            fail2banOn = snap.runningServices().stream()
                    .anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains("fail2ban"));
        }
        Set<String> wanted = selectedDetails == null || selectedDetails.isEmpty()
                ? Set.of("ufw", "fail2ban", "ssh-harden", "unattended")
                : new LinkedHashSet<>(selectedDetails);
        if (wanted.contains("ufw") && !ufwOn) {
            gaps.add("UFW inactif ou illisible");
        }
        if (wanted.contains("fail2ban") && !fail2banOn) {
            gaps.add("fail2ban absent");
        }
        if (wanted.contains("ssh-harden")) {
            if (snap.ssh() == null || !passwordAuthOff(snap.ssh().passwordAuthentication())) {
                gaps.add("mot de passe SSH encore accepté (ou config illisible)");
            }
            if (snap.ssh() == null || !rootLoginLocked(snap.ssh().permitRootLogin())) {
                gaps.add("root SSH encore autorisé (ou config illisible)");
            }
        }
        return gaps;
    }

    static boolean passwordAuthOff(String value) {
        String v = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return v.equals("no") || v.equals("false");
    }

    static boolean rootLoginLocked(String value) {
        String v = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return v.equals("no") || v.equals("prohibit-password") || v.equals("without-password");
    }

    static String securityReadyReason(ServerSnapshot snap, List<String> selectedDetails) {
        List<String> gaps = securityGaps(snap, selectedDetails);
        if (gaps.isEmpty()) {
            return "Baseline complet.";
        }
        return "Baseline incomplet (miner / webshell). Manque : "
                + String.join(" ; ", gaps)
                + ". Installer de nouveau est idempotent.";
    }

    static List<String> steps(String id) {
        return steps(id, List.of());
    }

    static List<String> steps(String id, List<String> selectedDetails) {
        Set<String> wanted = selectedDetails == null ? Set.of() : new LinkedHashSet<>(selectedDetails);
        return switch (id) {
            case "security" -> securitySteps(wanted);
            case "docker" -> dockerSteps(wanted);
            case "traefik" -> traefikSteps(wanted);
            case "mariadb" -> mariadbSteps(wanted);
            case "deploy" -> deploySteps(wanted);
            case "redis" -> List.of("Conteneur Redis, bind interne seulement");
            case "backups" -> List.of("Dump chiffré + copie hors-site + preuve de restore");
            default -> List.of();
        };
    }

    private static List<String> securitySteps(Set<String> wanted) {
        Set<String> pick = wanted.isEmpty()
                ? Set.of("ufw", "fail2ban", "ssh-harden", "unattended")
                : wanted;
        List<String> out = new ArrayList<>();
        if (pick.contains("ufw") || pick.contains("fail2ban") || pick.contains("unattended")) {
            out.add("apt : paquets cochés (ufw / fail2ban / unattended-upgrades)");
        }
        if (pick.contains("ufw")) {
            out.add("UFW : deny incoming, allow 22 seulement, enable");
        }
        if (pick.contains("fail2ban")) {
            out.add("fail2ban : jail sshd");
        }
        if (pick.contains("ssh-harden")) {
            out.add("SSH : clés seulement, root fermé, MaxAuthTries 3");
        }
        if (pick.contains("unattended")) {
            out.add("Mises à jour automatiques de sécurité");
        }
        return out;
    }

    private static List<String> dockerSteps(Set<String> wanted) {
        Set<String> pick = wanted.isEmpty()
                ? Set.of("engine", "compose", "docker-group")
                : wanted;
        List<String> out = new ArrayList<>();
        if (pick.contains("engine")) {
            out.add("Paquet docker.io + service docker");
        }
        if (pick.contains("compose")) {
            out.add("Docker Compose");
        }
        if (pick.contains("docker-group")) {
            out.add("Utilisateur SSH ajouté au groupe docker");
        }
        return out;
    }

    private static List<String> traefikSteps(Set<String> wanted) {
        Set<String> pick = wanted.isEmpty()
                ? Set.of("proxy", "https")
                : wanted;
        List<String> out = new ArrayList<>();
        if (pick.contains("proxy")) {
            out.add("Réseau Docker proxy + compose Traefik sous /opt/vps-autopilot/traefik");
            out.add("UFW : ouvrir 80 (Traefik seulement, pas d’app)");
        }
        if (pick.contains("https")) {
            out.add("Entrée 443 + Let’s Encrypt (certificats quand un domaine sera déclaré)");
            out.add("UFW : ouvrir 443");
        }
        return out;
    }

    private static List<String> mariadbSteps(Set<String> wanted) {
        Set<String> pick = wanted.isEmpty()
                ? Set.of("instance", "per-project")
                : wanted;
        List<String> out = new ArrayList<>();
        if (pick.contains("instance")) {
            out.add("Conteneur MariaDB 11.4, volume, réseau data, aucun port public");
            out.add("Mot de passe root généré dans /opt/vps-autopilot/mariadb/root.pw (600)");
        }
        if (pick.contains("per-project")) {
            out.add("Grants une base / un user : au déploiement de chaque app (pas maintenant)");
        }
        return out;
    }

    private static List<String> deploySteps(Set<String> wanted) {
        Set<String> pick = wanted.isEmpty()
                ? Set.of("user", "keys", "ssh-allow")
                : wanted;
        List<String> out = new ArrayList<>();
        if (pick.contains("user")) {
            out.add("Compte deploy, groupe docker, mot de passe verrouillé, pas de sudo");
        }
        if (pick.contains("keys")) {
            out.add("Copier la clé de l’agent dans ~deploy/.ssh/authorized_keys");
        }
        if (pick.contains("ssh-allow")) {
            out.add("AllowUsers : ajouter deploy (sans rouvrir le mot de passe SSH)");
        }
        return out;
    }
}
