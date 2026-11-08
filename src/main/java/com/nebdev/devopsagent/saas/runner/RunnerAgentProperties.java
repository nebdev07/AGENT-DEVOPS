package com.nebdev.devopsagent.saas.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Réglages du runner séparé ({@code autopilot.agent.*}, profil {@code runner}, ADR-055). */
@ConfigurationProperties("autopilot.agent")
public class RunnerAgentProperties {

    /** URL du control plane (https obligatoire hors boucle locale). */
    private String controlPlaneUrl = "";
    /** Jeton partagé avec le control plane ({@code AUTOPILOT_RUNNER_TOKEN}, ≥ 32 caractères). */
    private String token = "";
    /** Clé privée RSA PEM PKCS#8 (contenu) — ou {@link #privateKeyFile}. Jamais connue du control plane. */
    private String privateKey = "";
    private String privateKeyFile = "";
    /** Identifiant de cette instance (vide : nom d'hôte + suffixe aléatoire). */
    private String id = "";
    private int threads = 2;
    private long pollMillis = 3000;
    private long heartbeatMillis = 60_000;
    /** Autorise http:// vers un hôte non local (déconseillé : le jeton circulerait en clair). */
    private boolean allowInsecureHttp = false;
    /** Anti-SSRF côté runner : réseaux privés refusés sauf lab ou auto-hébergé. */
    private boolean allowPrivateTargets = false;
    private java.util.List<String> deniedTargets = new java.util.ArrayList<>();

    public boolean isAllowPrivateTargets() { return allowPrivateTargets; }
    public void setAllowPrivateTargets(boolean v) { this.allowPrivateTargets = v; }
    public java.util.List<String> getDeniedTargets() { return deniedTargets; }
    public void setDeniedTargets(java.util.List<String> v) { this.deniedTargets = v; }

    public String getControlPlaneUrl() { return controlPlaneUrl; }
    public void setControlPlaneUrl(String v) { this.controlPlaneUrl = v; }
    public String getToken() { return token; }
    public void setToken(String v) { this.token = v; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String v) { this.privateKey = v; }
    public String getPrivateKeyFile() { return privateKeyFile; }
    public void setPrivateKeyFile(String v) { this.privateKeyFile = v; }
    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public int getThreads() { return threads; }
    public void setThreads(int v) { this.threads = v; }
    public long getPollMillis() { return pollMillis; }
    public void setPollMillis(long v) { this.pollMillis = v; }
    public long getHeartbeatMillis() { return heartbeatMillis; }
    public void setHeartbeatMillis(long v) { this.heartbeatMillis = v; }
    public boolean isAllowInsecureHttp() { return allowInsecureHttp; }
    public void setAllowInsecureHttp(boolean v) { this.allowInsecureHttp = v; }
}
