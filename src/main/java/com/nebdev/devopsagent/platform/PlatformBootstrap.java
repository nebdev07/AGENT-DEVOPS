package com.nebdev.devopsagent.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seed SUPER_ADMIN fondateur avec mot de passe par défaut (à changer à la 1ʳᵉ connexion).
 */
@Component
@Profile("platform")
@Order(1)
public class PlatformBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrap.class);

    private final AccountStore accounts;
    private final RoleStore roles;
    private final PlatformProperties properties;

    public PlatformBootstrap(AccountStore accounts, RoleStore roles, PlatformProperties properties) {
        this.accounts = accounts;
        this.roles = roles;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        roles.list();
        ensureFounderAdmin();
        if (!accounts.isEmpty()) {
            return;
        }
        String email = resolveBootstrapEmail();
        if (email.isBlank() || accounts.findByEmail(email).isPresent()) {
            return;
        }
        String raw = resolveDefaultPassword();
        PlatformModels.Account admin = accounts.create(email, "Super admin", RoleStore.SUPER_ADMIN, raw, true);
        log.info("Compte SUPER_ADMIN créé : {} — change le mot de passe à la première connexion.", admin.email());
    }

    private void ensureFounderAdmin() throws Exception {
        String founder = properties.getFounderEmail();
        if (founder == null || founder.isBlank()) {
            founder = System.getenv().getOrDefault("PLATFORM_FOUNDER_EMAIL", "");
        }
        if (founder.isBlank()) {
            return;
        }
        var existing = accounts.findByEmail(founder);
        java.util.function.Supplier<String> raw = this::resolveDefaultPassword;
        if (existing.isPresent()) {
            PlatformModels.Account a = existing.get();
            if (!RoleStore.SUPER_ADMIN.equals(a.roleId()) || !a.active()) {
                accounts.setRole(a.id(), RoleStore.SUPER_ADMIN);
                accounts.setActive(a.id(), true);
            }
            if (forceDefaultPassword()) {
                accounts.setPassword(a.id(), raw.get(), true);
                log.warn("Mot de passe usine forcé pour {} (PLATFORM_FORCE_DEFAULT_PASSWORD) — à changer à la prochaine connexion.",
                        founder);
                return;
            }
            if (!a.hasPassword()) {
                accounts.setPassword(a.id(), raw.get(), true);
                log.info("Mot de passe par défaut posé pour {} — à changer à la prochaine connexion.", founder);
            }
            return;
        }
        accounts.create(founder, "Super admin", RoleStore.SUPER_ADMIN, raw.get(), true);
        log.info("Compte fondateur SUPER_ADMIN créé : {} — mot de passe par défaut (à changer).", founder);
    }

    private String resolveBootstrapEmail() {
        String email = properties.getBootstrapEmail();
        if (email == null || email.isBlank()) {
            email = System.getenv().getOrDefault("PLATFORM_BOOTSTRAP_EMAIL", "");
        }
        return email == null ? "" : email.strip();
    }

    private String resolveDefaultPassword() {
        String raw = properties.getDefaultPassword();
        if (raw == null || raw.isBlank()) {
            raw = System.getenv().getOrDefault("PLATFORM_DEFAULT_PASSWORD", "");
        }
        if (raw == null || raw.isBlank()) {
            // S6 : plus de mot de passe usine connu. Mot de passe initial aléatoire, montré une fois.
            if (generatedPassword == null) {
                generatedPassword = PasswordHashes.randomPassword();
                log.warn("Aucun PLATFORM_DEFAULT_PASSWORD : mot de passe initial généré (à changer à la 1re connexion) : {}",
                        generatedPassword);
            }
            raw = generatedPassword;
        }
        return raw;
    }

    private String generatedPassword;

    private boolean forceDefaultPassword() {
        if (properties.isForceDefaultPassword()) {
            return true;
        }
        String env = System.getenv().getOrDefault("PLATFORM_FORCE_DEFAULT_PASSWORD", "");
        return "1".equals(env) || "true".equalsIgnoreCase(env);
    }
}
