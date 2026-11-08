package com.nebdev.devopsagent.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devops.platform")
public class PlatformProperties {

    /** URL publique de base pour les liens magiques (ex. https://autopilot.exemple.com). */
    private String publicBaseUrl = "http://127.0.0.1:8088";
    private String bootstrapEmail = "";
    /** Compte SUPER_ADMIN toujours assuré au démarrage (profil platform). */
    private String founderEmail = "nebdev07@gmail.com";
    /**
     * Mot de passe initial du fondateur (changé à la 1ʳᵉ connexion).
     * Surcharge : {@code PLATFORM_DEFAULT_PASSWORD}.
     */
    /** Vide = mot de passe initial aléatoire, écrit une fois dans le log au démarrage (S6). */
    private String defaultPassword = "";
    private int magicLinkMinutes = 15;
    private int sessionHours = 12;
    /** En test / lab : le jeton est renvoyé dans la réponse JSON au lieu d’un vrai SMTP. */
    /** Défaut sûr (S5) : les liens ne sont jamais renvoyés dans la réponse HTTP. */
    private boolean mailDevCapture = false;
    /**
     * Au démarrage, remet le mot de passe du fondateur à {@link #defaultPassword}
     * avec {@code mustChangePassword=true}. Utile en local après oubli.
     * Surcharge : {@code PLATFORM_FORCE_DEFAULT_PASSWORD=true}.
     */
    private boolean forceDefaultPassword = false;
    private boolean cookieSecure = true;
    private String cookieName = "VPSAP_SESSION";

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
    }

    public String getBootstrapEmail() {
        return bootstrapEmail;
    }

    public void setBootstrapEmail(String bootstrapEmail) {
        this.bootstrapEmail = bootstrapEmail == null ? "" : bootstrapEmail;
    }

    public String getFounderEmail() {
        return founderEmail;
    }

    public void setFounderEmail(String founderEmail) {
        this.founderEmail = founderEmail == null ? "" : founderEmail.strip();
    }

    public String getDefaultPassword() {
        return defaultPassword;
    }

    public void setDefaultPassword(String defaultPassword) {
        this.defaultPassword = defaultPassword == null ? "" : defaultPassword;
    }

    public int getMagicLinkMinutes() {
        return magicLinkMinutes;
    }

    public void setMagicLinkMinutes(int magicLinkMinutes) {
        this.magicLinkMinutes = magicLinkMinutes;
    }

    public int getSessionHours() {
        return sessionHours;
    }

    public void setSessionHours(int sessionHours) {
        this.sessionHours = sessionHours;
    }

    public boolean isMailDevCapture() {
        return mailDevCapture;
    }

    public void setMailDevCapture(boolean mailDevCapture) {
        this.mailDevCapture = mailDevCapture;
    }

    public boolean isForceDefaultPassword() {
        return forceDefaultPassword;
    }

    public void setForceDefaultPassword(boolean forceDefaultPassword) {
        this.forceDefaultPassword = forceDefaultPassword;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName == null || cookieName.isBlank() ? "VPSAP_SESSION" : cookieName;
    }
}
