package com.nebdev.devopsagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devops")
public class DevopsProperties {

    private Ssh ssh = new Ssh();
    private String stateDir = "./state";
    private String labFixture = "classpath:fixtures/ubuntu-vps2-blank.txt";

    public Ssh getSsh() {
        return ssh;
    }

    public void setSsh(Ssh ssh) {
        this.ssh = ssh;
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
