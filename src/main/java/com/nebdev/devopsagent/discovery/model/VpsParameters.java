package com.nebdev.devopsagent.discovery.model;

public record VpsParameters(
        String name,
        String host,
        int port,
        String user,
        String os
) {
    public static VpsParameters from(String name, String host, Integer port, String user, String os) {
        return new VpsParameters(
                name == null ? "" : name.trim(),
                host == null ? "" : host.trim(),
                port == null || port <= 0 ? 22 : port,
                user == null ? "" : user.trim(),
                os == null || os.isBlank() ? "auto" : os.trim().toLowerCase()
        );
    }
}
