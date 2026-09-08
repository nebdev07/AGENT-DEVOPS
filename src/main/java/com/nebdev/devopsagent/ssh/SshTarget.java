package com.nebdev.devopsagent.ssh;

public record SshTarget(String host, int port, String user) {

    public SshTarget {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Hôte SSH vide.");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("Utilisateur SSH vide.");
        }
        if (port <= 0) {
            port = 22;
        }
    }
}
