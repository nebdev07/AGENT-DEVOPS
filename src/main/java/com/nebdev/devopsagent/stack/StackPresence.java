package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.discovery.model.LiveSnapshot;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Détails déjà présents sur le VPS (d’après le dernier snapshot live).
 * Ils ne sont pas cochés par défaut et n’ouvrent pas d’écart de politique.
 */
public final class StackPresence {

    private StackPresence() {}

    public static Map<String, List<String>> of(ServerSnapshot snap) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (snap == null || !LiveSnapshot.isLive(snap)) {
            return out;
        }
        List<String> security = new ArrayList<>();
        if (ufwOn(snap)) {
            security.add("ufw");
        }
        if (fail2banOn(snap)) {
            security.add("fail2ban");
        }
        if (sshHardened(snap)) {
            security.add("ssh-harden");
        }
        if (unattendedOn(snap)) {
            security.add("unattended");
        }
        if (!security.isEmpty()) {
            out.put("security", List.copyOf(security));
        }
        List<String> docker = new ArrayList<>();
        if (softwareOn(snap.docker())) {
            docker.add("engine");
            if (composeOn(snap)) {
                docker.add("compose");
            }
            if (dockerGroupOn(snap)) {
                docker.add("docker-group");
            }
        }
        if (!docker.isEmpty()) {
            out.put("docker", List.copyOf(docker));
        }
        if (softwareOn(snap.traefik())) {
            out.put("traefik", List.of("proxy", "https"));
        }
        if (softwareOn(snap.mariadb())) {
            out.put("mariadb", List.of("instance"));
        }
        if (softwareOn(snap.deploy())) {
            out.put("deploy", List.of("user", "keys", "ssh-allow"));
        }
        if (softwareOn(snap.redis())) {
            out.put("redis", List.of("internal"));
        }
        return out;
    }

    public static Map<String, List<String>> subtract(
            Map<String, List<String>> details,
            Map<String, List<String>> present
    ) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        if (present == null || present.isEmpty()) {
            return details;
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : details.entrySet()) {
            Set<String> skip = Set.copyOf(present.getOrDefault(e.getKey(), List.of()));
            List<String> kept = e.getValue() == null
                    ? List.of()
                    : e.getValue().stream().filter(id -> !skip.contains(id)).toList();
            out.put(e.getKey(), kept);
        }
        return out;
    }

    static boolean ufwOn(ServerSnapshot snap) {
        if (snap.firewall() == null) {
            return false;
        }
        String ufw = String.valueOf(snap.firewall().ufwStatus()).toLowerCase(Locale.ROOT);
        return ufw.equals("active") || ufw.equals("enabled");
    }

    static boolean fail2banOn(ServerSnapshot snap) {
        if (snap.firewall() != null) {
            String f2b = String.valueOf(snap.firewall().fail2banStatus()).toLowerCase(Locale.ROOT);
            if (f2b.equals("active") || f2b.equals("installed")) {
                return true;
            }
        }
        if (snap.runningServices() == null) {
            return false;
        }
        return snap.runningServices().stream()
                .anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains("fail2ban"));
    }

    static boolean sshHardened(ServerSnapshot snap) {
        if (snap.ssh() == null) {
            return false;
        }
        return InstallPlanBuilder.passwordAuthOff(snap.ssh().passwordAuthentication())
                && InstallPlanBuilder.rootLoginLocked(snap.ssh().permitRootLogin());
    }

    static boolean unattendedOn(ServerSnapshot snap) {
        if (snap.runningServices() == null) {
            return false;
        }
        return snap.runningServices().stream()
                .anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains("unattended-upgrades"));
    }

    private static boolean softwareOn(ServerSnapshot.SoftwarePresence p) {
        return p != null && p.present();
    }

    private static boolean composeOn(ServerSnapshot snap) {
        String blob = dockerBlob(snap);
        if (blob.contains("compose_absent")) {
            return false;
        }
        return blob.contains("compose");
    }

    static boolean dockerGroupOn(ServerSnapshot snap) {
        if (snap == null || snap.docker() == null) {
            return false;
        }
        for (String line : dockerBlob(snap).split("\\R")) {
            String t = line.trim();
            if (!t.startsWith("groups=")) {
                continue;
            }
            String groups = t.substring("groups=".length());
            for (String token : groups.split("\\s+")) {
                if ("docker".equals(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String dockerBlob(ServerSnapshot snap) {
        if (snap == null || snap.docker() == null) {
            return "";
        }
        return ((snap.docker().version() == null ? "" : snap.docker().version())
                + "\n"
                + (snap.docker().detail() == null ? "" : snap.docker().detail()))
                .toLowerCase(Locale.ROOT);
    }
}
