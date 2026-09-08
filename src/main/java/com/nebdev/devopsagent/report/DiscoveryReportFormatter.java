package com.nebdev.devopsagent.report;

import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryReportFormatter {

    public String format(ServerSnapshot s) {
        StringBuilder b = new StringBuilder();
        b.append("SERVER DISCOVERY\n");
        b.append("================\n\n");
        b.append(row("Source", s.source()));
        b.append(row("Host", s.host()));
        b.append(row("Hostname", s.hostname()));
        b.append(row("OS", blankToDash(s.osPretty())));
        b.append(row("OS family", blankToDash(s.osFamily())));
        b.append(row("OS déclaré", blankToDash(s.declaredOs())));
        b.append(row("Kernel", s.kernel()));
        b.append(row("Arch", s.arch()));
        b.append(row("CPU", s.cpu().vcpus() + " vCPU  " + blankToDash(s.cpu().model())));
        b.append(row("RAM", humanBytes(s.memory().totalBytes())
                + " total, " + humanBytes(s.memory().availableBytes()) + " dispo"));
        b.append(row("Disk /", humanBytes(s.disk().totalBytes())
                + " total, " + humanBytes(s.disk().usedBytes()) + " utilisés"));
        b.append(row("IPs", s.ipAddresses().isEmpty() ? "—" : String.join(", ", s.ipAddresses())));
        b.append(row("User SSH", s.remoteUser()));
        b.append('\n');
        b.append(row("Docker", presence(s.docker())));
        b.append(row("Nginx", presence(s.nginx())));
        b.append(row("MariaDB/MySQL", presence(s.mariadb())));
        b.append(row("Redis", presence(s.redis())));
        b.append(row("Traefik", presence(s.traefik())));
        b.append(row("Compte deploy", presence(s.deploy())));
        b.append(row("Apps", s.apps() == null || s.apps().isEmpty() ? "—" : String.join(", ", s.apps())));
        b.append('\n');
        b.append(row("SSH écoute", s.ssh().listening() ? "oui" : "non"));
        b.append(row("PermitRootLogin", blankToDash(s.ssh().permitRootLogin())));
        b.append(row("PasswordAuth", blankToDash(s.ssh().passwordAuthentication())));
        b.append(row("Pare-feu", blankToDash(s.firewall().engine()) + " / " + blankToDash(s.firewall().ufwStatus())
                + " · fail2ban=" + blankToDash(s.firewall().fail2banStatus())));
        b.append(row("TCP listen", s.firewall().listeningTcpPorts().toString()));
        b.append('\n');
        b.append("Services running: ").append(s.runningServices().size()).append('\n');
        b.append('\n');
        b.append("RISK        READ only — aucune modification du serveur\n");
        if (!s.warnings().isEmpty()) {
            b.append('\n');
            b.append("Warnings:\n");
            for (String w : s.warnings()) {
                b.append("  - ").append(w).append('\n');
            }
        }
        return b.toString();
    }

    private static String presence(ServerSnapshot.SoftwarePresence p) {
        if (p == null || !p.present()) {
            return "absent";
        }
        if (p.version() != null && !p.version().isBlank()) {
            return "présent  (" + p.version() + ")";
        }
        return "présent";
    }

    private static String row(String label, String value) {
        return String.format("%-16s %s%n", label, value == null || value.isBlank() ? "—" : value);
    }

    private static String blankToDash(String v) {
        return v == null || v.isBlank() ? "—" : v;
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) {
            return String.format(java.util.Locale.ROOT, "%.1f GB", gb);
        }
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(java.util.Locale.ROOT, "%.0f MB", mb);
    }
}
