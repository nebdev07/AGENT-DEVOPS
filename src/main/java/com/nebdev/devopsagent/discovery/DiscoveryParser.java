package com.nebdev.devopsagent.discovery;

import com.nebdev.devopsagent.discovery.model.OsFamily;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import com.nebdev.devopsagent.discovery.model.VpsParameters;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.CpuInfo;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.DiskInfo;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.FirewallInfo;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.ListeningPort;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.MemoryInfo;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.SoftwarePresence;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.SshDaemonInfo;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DiscoveryParser {

    private static final Pattern SECTION = Pattern.compile("(?m)^###([A-Z][A-Z0-9]*)###\\s*$");
    private static final Pattern SS_LISTEN = Pattern.compile(
            "(?i)^(tcp6?|udp6?)\\s+\\S+\\s+\\d+\\s+\\d+\\s+(\\S+):(\\d+)\\b");
    private static final Pattern NETSTAT_LISTEN = Pattern.compile(
            "(?i)^(tcp6?|udp6?)\\s+\\d+\\s+\\d+\\s+(\\S+):(\\d+)\\b");

    public ServerSnapshot parse(String raw, String source, String configuredHost) {
        return parse(raw, source, configuredHost, null);
    }

    public ServerSnapshot parse(String raw, String source, String configuredHost, VpsParameters params) {
        Map<String, String> sections = splitSections(raw);
        List<String> warnings = new ArrayList<>();

        Map<String, String> os = parseOsRelease(sections.getOrDefault("OS", ""));
        String[] kernelLines = lines(sections.getOrDefault("KERNEL", ""));
        String arch = kernelLines.length >= 3 ? kernelLines[2].trim() : "";
        String kernel = kernelLines.length >= 2
                ? (kernelLines[0] + " " + kernelLines[1]).trim()
                : "";

        String[] cpuLines = lines(sections.getOrDefault("CPU", ""));
        int vcpus = 0;
        String model = "";
        if (cpuLines.length > 0) {
            try {
                vcpus = Integer.parseInt(cpuLines[0].trim());
            } catch (NumberFormatException e) {
                warnings.add("nproc illisible");
            }
        }
        if (cpuLines.length > 1) {
            model = cpuLines[1].trim();
        }

        MemoryInfo memory = parseMemory(sections.getOrDefault("MEM", ""), warnings);
        DiskInfo disk = parseDisk(sections.getOrDefault("DISK", ""), warnings);

        String[] hostLines = lines(sections.getOrDefault("HOST", ""));
        String hostname = hostLines.length > 0 ? hostLines[0].trim() : "";
        List<String> ips = new ArrayList<>();
        if (hostLines.length > 1) {
            for (String token : hostLines[1].trim().split("\\s+")) {
                if (!token.isBlank()) {
                    ips.add(token);
                }
            }
        }

        List<ListeningPort> ports = parsePorts(sections.getOrDefault("PORTS", ""));
        List<String> services = new ArrayList<>();
        for (String line : lines(sections.getOrDefault("SERVICES", ""))) {
            if (!line.isBlank()) {
                services.add(line.trim());
            }
        }

        SoftwarePresence docker = parseSoftware(sections.getOrDefault("DOCKER", ""));
        SoftwarePresence nginx = parseSoftware(sections.getOrDefault("NGINX", ""));
        SoftwarePresence mariadb = parseSoftware(sections.getOrDefault("MARIADB", ""));
        SoftwarePresence redis = parseSoftware(sections.getOrDefault("REDIS", ""));
        SoftwarePresence traefik = parseSoftware(sections.getOrDefault("TRAEFIK", ""));
        SoftwarePresence deploy = parseSoftware(sections.getOrDefault("DEPLOY", ""));
        SoftwarePresence backups = parseSoftware(sections.getOrDefault("BACKUPS", ""));
        List<String> apps = parseAppIds(sections.getOrDefault("APPS", ""));

        SshDaemonInfo ssh = parseSsh(sections.getOrDefault("SSHD", ""), ports);
        String fwBody = sections.containsKey("FIREWALL") ? sections.get("FIREWALL") : sections.getOrDefault("UFW", "");
        FirewallInfo firewall = parseFirewall(fwBody, sections.getOrDefault("FAIL2BAN", ""), ports, services);

        String[] who = lines(sections.getOrDefault("WHO", ""));
        String remoteUser = who.length > 0 ? who[0].trim() : "";

        String host = configuredHost == null || configuredHost.isBlank() ? hostname : configuredHost;
        String osId = os.getOrDefault("ID", "").toLowerCase(Locale.ROOT).replace("\"", "");
        OsFamily detected = OsFamily.fromOsReleaseId(osId);
        OsFamily declared = OsFamily.fromDeclared(params == null ? "auto" : params.os());
        if (!declared.compatibleWith(detected)) {
            warnings.add("OS déclaré " + declared + " ≠ détecté " + detected + " (" + osId + ")");
        }
        String profileName = params == null ? "" : params.name();
        String declaredOs = params == null ? "auto" : params.os();
        addSecurityWarnings(warnings, ssh, nginx, sections.getOrDefault("SIGNALS", ""), source);

        return new ServerSnapshot(
                Instant.now(),
                source,
                host,
                hostname,
                profileName,
                declaredOs,
                detected.name().toLowerCase(Locale.ROOT),
                osId,
                os.getOrDefault("VERSION_ID", "").replace("\"", ""),
                os.getOrDefault("PRETTY_NAME", "").replace("\"", ""),
                kernel,
                arch,
                new CpuInfo(vcpus, model),
                memory,
                disk,
                List.copyOf(ips),
                List.copyOf(ports),
                List.copyOf(services),
                docker,
                nginx,
                mariadb,
                redis,
                traefik,
                deploy,
                backups,
                apps,
                ssh,
                firewall,
                remoteUser,
                List.copyOf(warnings)
        );
    }

    static Map<String, String> splitSections(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = SECTION.matcher(raw);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
            spans.add(new int[]{m.start(), m.end()});
        }
        for (int i = 0; i < names.size(); i++) {
            int contentStart = spans.get(i)[1];
            int contentEnd = i + 1 < spans.size() ? spans.get(i + 1)[0] : raw.length();
            out.put(names.get(i), raw.substring(contentStart, contentEnd).trim());
        }
        return out;
    }

    private static Map<String, String> parseOsRelease(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : lines(body)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static MemoryInfo parseMemory(String body, List<String> warnings) {
        long totalKb = 0;
        long availableKb = 0;
        for (String line : lines(body)) {
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            long value;
            try {
                value = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (line.startsWith("MemTotal:")) {
                totalKb = value;
            } else if (line.startsWith("MemAvailable:")) {
                availableKb = value;
            }
        }
        if (totalKb == 0) {
            warnings.add("mémoire illisible");
        }
        return new MemoryInfo(totalKb * 1024, availableKb * 1024);
    }

    private static DiskInfo parseDisk(String body, List<String> warnings) {
        String[] parts = body.trim().split("\\s+");
        if (parts.length < 6) {
            warnings.add("disque racine illisible");
            return new DiskInfo(0, 0, "/");
        }
        try {
            long total = Long.parseLong(parts[1]);
            long used = Long.parseLong(parts[2]);
            return new DiskInfo(total, used, parts[5]);
        } catch (NumberFormatException e) {
            warnings.add("disque racine illisible");
            return new DiskInfo(0, 0, "/");
        }
    }

    private static List<ListeningPort> parsePorts(String body) {
        List<ListeningPort> ports = new ArrayList<>();
        for (String line : lines(body)) {
            Matcher ss = SS_LISTEN.matcher(line.trim() + " ");
            Matcher ns = NETSTAT_LISTEN.matcher(line.trim());
            Matcher hit = ss.find() ? ss : (ns.find() ? ns : null);
            if (hit == null) {
                continue;
            }
            String proto = hit.group(1).toLowerCase(Locale.ROOT);
            String addr = hit.group(2);
            int port = Integer.parseInt(hit.group(3));
            ports.add(new ListeningPort(proto, addr, port));
        }
        return ports;
    }

    static List<String> parseAppIds(String body) {
        List<String> out = new ArrayList<>();
        for (String line : lines(body)) {
            String t = line.trim();
            if (t.isEmpty() || t.equalsIgnoreCase("ABSENT") || t.equalsIgnoreCase("UNKNOWN")) {
                continue;
            }
            out.add(t);
        }
        return List.copyOf(out);
    }

    private static SoftwarePresence parseSoftware(String body) {
        String trimmed = body.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("ABSENT") || trimmed.equals("UNKNOWN")) {
            return new SoftwarePresence(false, null, trimmed.isEmpty() ? "ABSENT" : firstLine(trimmed));
        }
        boolean present = trimmed.startsWith("PRESENT") || !trimmed.startsWith("ABSENT");
        if (trimmed.startsWith("ABSENT")) {
            present = false;
        }
        String version = null;
        for (String line : lines(trimmed)) {
            if (line.contains("version") || line.matches("(?i).*docker.*\\d.*") || line.matches("(?i)mysql.*")) {
                version = line.trim();
                break;
            }
        }
        return new SoftwarePresence(present, version, trimmed);
    }

    private static SshDaemonInfo parseSsh(String body, List<ListeningPort> ports) {
        String permit = null;
        String password = null;
        Integer sshPort = null;
        for (String line : lines(body)) {
            String[] p = line.trim().split("\\s+", 2);
            if (p.length < 2) {
                continue;
            }
            switch (p[0].toLowerCase(Locale.ROOT)) {
                case "permitrootlogin" -> permit = p[1];
                case "passwordauthentication" -> password = p[1];
                case "port" -> {
                    try {
                        sshPort = Integer.parseInt(p[1]);
                    } catch (NumberFormatException ignored) {
                        // ignore
                    }
                }
                default -> {
                }
            }
        }
        int expected = sshPort != null ? sshPort : 22;
        boolean listening = ports.stream().anyMatch(p -> p.port() == expected && p.protocol().startsWith("tcp"));
        return new SshDaemonInfo(listening, permit, password, sshPort);
    }

    private static FirewallInfo parseFirewall(
            String body,
            String fail2banBody,
            List<ListeningPort> ports,
            List<String> services
    ) {
        String engine = "unknown";
        String status = "unknown";
        for (String line : lines(body)) {
            String t = line.trim();
            if (t.startsWith("ENGINE=")) {
                engine = t.substring("ENGINE=".length()).trim();
            }
        }
        String blob = body.toLowerCase(Locale.ROOT);
        if (blob.contains("inactive") || blob.contains("not running")) {
            status = "inactive";
        } else if (blob.contains("active") || blob.contains("running")) {
            status = "active";
        }
        if ("ufw".equals(engine) && blob.contains("status: inactive")) {
            status = "inactive";
        }
        String fail2ban = parseFail2ban(fail2banBody, services);
        List<Integer> tcp = ports.stream()
                .filter(p -> p.protocol().startsWith("tcp"))
                .map(ListeningPort::port)
                .distinct()
                .sorted()
                .toList();
        return new FirewallInfo(engine, status, fail2ban, tcp);
    }

    static void addSecurityWarnings(
            List<String> warnings,
            SshDaemonInfo ssh,
            SoftwarePresence nginx,
            String signalsBody,
            String source
    ) {
        String signals = signalsBody == null ? "" : signalsBody.toUpperCase(Locale.ROOT);
        if (signals.contains("MINER_PROCESS")) {
            warnings.add("Processus type miner détecté (xmrig/kinsing/…). Isoler le VPS, ne pas ouvrir 80/443.");
        }
        if (signals.contains("PHP_PRESENT")) {
            warnings.add("PHP présent sur l’hôte (risque webshell). Pas de webroot PHP hors conteneur.");
        }
        if (signals.contains("APACHE_PRESENT")) {
            warnings.add("Apache présent sur l’hôte. Préfère Traefik + conteneurs.");
        }
        if (signals.contains("NGINX_HOST_PRESENT") || (nginx != null && nginx.present())) {
            warnings.add("Nginx hôte présent. Ne pas servir d’app depuis un webroot.");
        }
        if (signals.contains("MARIADB_HOST")) {
            warnings.add("MariaDB/MySQL présent sur l’hôte. L’instance prévue est un conteneur, pas un paquet apt.");
        }
        boolean live = source != null && source.startsWith("ssh:");
        if (live && ssh != null) {
            String pwd = ssh.passwordAuthentication() == null ? "" : ssh.passwordAuthentication().toLowerCase(Locale.ROOT);
            String root = ssh.permitRootLogin() == null ? "" : ssh.permitRootLogin().toLowerCase(Locale.ROOT);
            if (pwd.isBlank() && root.isBlank()) {
                warnings.add("Config SSH illisible (sshd -T). Relancer l’analyse ; sudo NOPASSWD aide.");
            } else {
                if (pwd.equals("yes")) {
                    warnings.add("SSH accepte encore le mot de passe (brute-force). Relancer Installer Sécurité.");
                }
                if (root.equals("yes")) {
                    warnings.add("Root SSH encore autorisé. Relancer Installer Sécurité.");
                }
            }
        }
    }

    static String parseFail2ban(String body, List<String> services) {
        String blob = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (blob.contains("active")) {
            return "active";
        }
        if (services != null && services.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("fail2ban"))) {
            return "active";
        }
        if (blob.contains("installed")) {
            return "installed";
        }
        if (blob.contains("absent")) {
            return "absent";
        }
        return "unknown";
    }

    private static String[] lines(String body) {
        if (body == null || body.isBlank()) {
            return new String[0];
        }
        return body.split("\\R");
    }

    private static String firstLine(String body) {
        String[] l = lines(body);
        return l.length == 0 ? "" : l[0].trim();
    }
}
