package com.nebdev.devopsagent.discovery.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record ServerSnapshot(
        Instant capturedAt,
        String source,
        String host,
        String hostname,
        String profileName,
        String declaredOs,
        String osFamily,
        String osId,
        String osVersion,
        String osPretty,
        String kernel,
        String arch,
        CpuInfo cpu,
        MemoryInfo memory,
        DiskInfo disk,
        List<String> ipAddresses,
        List<ListeningPort> listeningPorts,
        List<String> runningServices,
        SoftwarePresence docker,
        SoftwarePresence nginx,
        SoftwarePresence mariadb,
        SoftwarePresence redis,
        SoftwarePresence traefik,
        SoftwarePresence deploy,
        SoftwarePresence backups,
        List<String> apps,
        SshDaemonInfo ssh,
        FirewallInfo firewall,
        String remoteUser,
        List<String> warnings,
        Hygiene hygiene
) {
    /**
     * Hygiène de l’hôte pour l’audit (BIZ-04). Champs {@code null} = non mesuré (analyse antérieure, autre OS).
     * @param signals jetons bruts de la section SIGNALS (PHP_PRESENT, MINER_PROCESS…)
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Hygiene(
            Integer pendingUpdates,
            Integer securityUpdates,
            Boolean rebootRequired,
            Boolean autoUpdates,
            Long uptimeSeconds,
            Boolean backupTimer,
            List<BackupFile> backupFiles,
            List<String> signals,
            Boolean drillTimer,
            AutoDrill autoDrill
    ) {
        public Hygiene(Integer pendingUpdates, Integer securityUpdates, Boolean rebootRequired, Boolean autoUpdates,
                       Long uptimeSeconds, Boolean backupTimer, List<BackupFile> backupFiles, List<String> signals) {
            this(pendingUpdates, securityUpdates, rebootRequired, autoUpdates, uptimeSeconds, backupTimer,
                    backupFiles, signals, null, null);
        }
    }

    public record BackupFile(String name, long modifiedEpochSeconds, long sizeBytes) {}

    /**
     * Dernier exercice de restauration automatique fait par le VPS (BIZ-12, {@code drill-last.txt}).
     * @param lines DRILL_DB=base:attendu:obtenu, DRILL_VOL=volume:attendu:obtenu, DRILL_PROBLEM=…
     */
    public record AutoDrill(String at, boolean ok, String dump, List<String> lines) {}

    public record CpuInfo(int vcpus, String model) {}

    public record MemoryInfo(long totalBytes, long availableBytes) {}

    public record DiskInfo(long totalBytes, long usedBytes, String mount) {}

    public record ListeningPort(String protocol, String address, int port) {}

    public record SshDaemonInfo(
            boolean listening,
            String permitRootLogin,
            String passwordAuthentication,
            Integer configuredPort
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record FirewallInfo(
            String engine,
            String ufwStatus,
            String fail2banStatus,
            List<Integer> listeningTcpPorts
    ) {}

    public record SoftwarePresence(boolean present, String version, String detail) {}
}
