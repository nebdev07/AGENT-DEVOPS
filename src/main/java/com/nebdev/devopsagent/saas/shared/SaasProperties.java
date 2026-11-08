package com.nebdev.devopsagent.saas.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Réglages du profil saas ({@code autopilot.*}). Validés au démarrage par {@link SaasStartupChecks}. */
@ConfigurationProperties("autopilot")
public class SaasProperties {

    private String publicUrl = "http://localhost:4200";
    private String mailFrom = "no-reply@localhost";
    private final Crypto crypto = new Crypto();
    private final Auth auth = new Auth();
    private final Operations operations = new Operations();
    private final Runner runner = new Runner();
    private final Billing billing = new Billing();
    private final Ai ai = new Ai();
    private final Servers servers = new Servers();

    public Servers getServers() { return servers; }

    /** Cibles SSH (anti-SSRF, {@link TargetPolicy}). */
    public static class Servers {
        /** true : réseaux privés et boucle locale acceptés (lab, auto-hébergé). Jamais sur l'instance publique. */
        private boolean allowPrivateTargets = false;
        /** Hôtes ou adresses IP que la plateforme refuse toujours de cibler. */
        private java.util.List<String> deniedTargets = new java.util.ArrayList<>();
        public boolean isAllowPrivateTargets() { return allowPrivateTargets; }
        public void setAllowPrivateTargets(boolean v) { this.allowPrivateTargets = v; }
        public java.util.List<String> getDeniedTargets() { return deniedTargets; }
        public void setDeniedTargets(java.util.List<String> v) { this.deniedTargets = v; }
        public java.util.Set<String> denied() { return TargetPolicy.normalize(deniedTargets); }
    }

    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String v) { this.publicUrl = v; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String v) { this.mailFrom = v; }
    public Crypto getCrypto() { return crypto; }
    public Auth getAuth() { return auth; }
    public Operations getOperations() { return operations; }
    public Runner getRunner() { return runner; }
    public Billing getBilling() { return billing; }
    public Ai getAi() { return ai; }

    public static class Crypto {
        private String masterKey = "";
        private String masterKeyId = "k1";
        public String getMasterKey() { return masterKey; }
        public void setMasterKey(String v) { this.masterKey = v; }
        public String getMasterKeyId() { return masterKeyId; }
        public void setMasterKeyId(String v) { this.masterKeyId = v; }
    }

    public static class Auth {
        private int sessionHours = 12;
        private int maxFailedLogins = 10;
        private int lockoutMinutes = 15;
        /** Personnel INOVNE : rôle effectif seulement avec MFA active (ne désactiver qu'en lab). */
        private boolean staffMfaRequired = true;
        public boolean isStaffMfaRequired() { return staffMfaRequired; }
        public void setStaffMfaRequired(boolean v) { this.staffMfaRequired = v; }
        public int getSessionHours() { return sessionHours; }
        public void setSessionHours(int v) { this.sessionHours = v; }
        public int getMaxFailedLogins() { return maxFailedLogins; }
        public void setMaxFailedLogins(int v) { this.maxFailedLogins = v; }
        public int getLockoutMinutes() { return lockoutMinutes; }
        public void setLockoutMinutes(int v) { this.lockoutMinutes = v; }
    }

    public static class Operations {
        private int approvalTtlMinutes = 60;
        private int proposalTtlHours = 24;
        private int outputMaxBytes = 65536;
        private int outputRetentionDays = 90;
        public int getApprovalTtlMinutes() { return approvalTtlMinutes; }
        public void setApprovalTtlMinutes(int v) { this.approvalTtlMinutes = v; }
        public int getProposalTtlHours() { return proposalTtlHours; }
        public void setProposalTtlHours(int v) { this.proposalTtlHours = v; }
        public int getOutputMaxBytes() { return outputMaxBytes; }
        public void setOutputMaxBytes(int v) { this.outputMaxBytes = v; }
        public int getOutputRetentionDays() { return outputRetentionDays; }
        public void setOutputRetentionDays(int v) { this.outputRetentionDays = v; }
    }

    public static class Runner {
        private String mode = "embedded";
        private String token = "";
        private long pollMillis = 2000;
        private int threads = 4;
        private int staleAfterMinutes = 30;
        /** Clé publique RSA du runner séparé (PEM) : en mode remote, les secrets sont scellés pour lui (ADR-055). */
        private String publicKey = "";
        /** false : aucune boucle planifiée (tests, maintenance) ; les files restent pilotables à la main. */
        private boolean scheduling = true;
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String v) { this.publicKey = v; }
        public boolean isScheduling() { return scheduling; }
        public void setScheduling(boolean v) { this.scheduling = v; }
        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v; }
        public boolean isEmbedded() { return !"remote".equalsIgnoreCase(mode); }
        public String getToken() { return token; }
        public void setToken(String v) { this.token = v; }
        public long getPollMillis() { return pollMillis; }
        public void setPollMillis(long v) { this.pollMillis = v; }
        public int getThreads() { return threads; }
        public void setThreads(int v) { this.threads = v; }
        public int getStaleAfterMinutes() { return staleAfterMinutes; }
        public void setStaleAfterMinutes(int v) { this.staleAfterMinutes = v; }
    }

    public static class Billing {
        private String provider = "manual";
        private String webhookSecret = "";
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String v) { this.webhookSecret = v; }
    }

    public static class Ai {
        private String provider = "none";
        private String model = "claude-opus-5-5";
        private String apiKey = "";
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
    }
}
