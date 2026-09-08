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
        List<String> apps,
        SshDaemonInfo ssh,
        FirewallInfo firewall,
        String remoteUser,
        List<String> warnings
) {
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
