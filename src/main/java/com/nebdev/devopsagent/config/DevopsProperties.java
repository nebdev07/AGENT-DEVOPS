package com.nebdev.devopsagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "devops")
public class DevopsProperties {

    private Ssh ssh = new Ssh();
    private Guard guard = new Guard();
    private String stateDir = "./state";
    private String labFixture = "classpath:fixtures/ubuntu-vps2-blank.txt";

    public Ssh getSsh() {
        return ssh;
    }

    public void setSsh(Ssh ssh) {
        this.ssh = ssh;
    }

    public Guard getGuard() {
        return guard;
    }

    public void setGuard(Guard guard) {
        this.guard = guard;
    }

    public String getStateDir() {
        return stateDir;
    }

    public void setStateDir(String stateDir) {
        this.stateDir = stateDir;
    }

    public String getLabFixture() {
        return labFixture;
    }

    public void setLabFixture(String labFixture) {
        this.labFixture = labFixture;
    }

    public boolean isSshConfigured() {
        Ssh s = ssh;
        return s.getHost() != null && !s.getHost().isBlank()
                && s.getUser() != null && !s.getUser().isBlank()
                && hasPrivateKey();
    }

    public boolean hasPrivateKey() {
        return ssh.getPrivateKeyPath() != null && !ssh.getPrivateKeyPath().isBlank();
    }

    /** Serveurs protégés propres à chaque opérateur : jamais versionnés (state/agent-local.yml). */
    public static class Guard {
        private List<String> blockedHosts = new ArrayList<>();
        private List<String> readOnlyHosts = new ArrayList<>();

        public List<String> getBlockedHosts() {
            return blockedHosts;
        }

        public void setBlockedHosts(List<String> blockedHosts) {
            this.blockedHosts = blockedHosts == null ? new ArrayList<>() : blockedHosts;
        }

        public List<String> getReadOnlyHosts() {
            return readOnlyHosts;
        }

        public void setReadOnlyHosts(List<String> readOnlyHosts) {
            this.readOnlyHosts = readOnlyHosts == null ? new ArrayList<>() : readOnlyHosts;
        }
    }

    public static class Ssh {
        private String host = "";
        private int port = 22;
        private String user = "";
        private String privateKeyPath = "";
        private String passphrase = "";
        private boolean trustUnknown = false;
        private int timeoutSeconds = 45;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public String getPassphrase() {
            return passphrase;
        }

        public void setPassphrase(String passphrase) {
            this.passphrase = passphrase;
        }

        public boolean isTrustUnknown() {
            return trustUnknown;
        }

        public void setTrustUnknown(boolean trustUnknown) {
            this.trustUnknown = trustUnknown;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
