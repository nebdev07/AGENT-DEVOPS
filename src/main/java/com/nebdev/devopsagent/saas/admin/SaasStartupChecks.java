package com.nebdev.devopsagent.saas.admin;

import com.nebdev.devopsagent.saas.shared.RlsContext;
import com.nebdev.devopsagent.saas.shared.SaasProperties;

import com.nebdev.devopsagent.saas.identity.IdentityRepositories;
import com.nebdev.devopsagent.saas.identity.PlatformStaff;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refus de démarrer sur une configuration dangereuse, puis amorçage du premier administrateur INOVNE :
 * l'utilisateur {@code AUTOPILOT_BOOTSTRAP_ADMIN_EMAIL} (déjà inscrit) devient PLATFORM_ADMIN, une seule fois,
 * tant qu'aucun personnel n'existe.
 */
@Component
public class SaasStartupChecks implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SaasStartupChecks.class);

    @PersistenceContext
    private EntityManager em;
    private final SaasProperties properties;
    private final IdentityRepositories.Users users;
    private final IdentityRepositories.Staff staff;
    private final RlsContext rls;
    private final String bootstrapAdmin;
    private final com.nebdev.devopsagent.saas.credential.CredentialService vault;

    public SaasStartupChecks(SaasProperties properties, IdentityRepositories.Users users, IdentityRepositories.Staff staff,
                             RlsContext rls, @Value("${AUTOPILOT_BOOTSTRAP_ADMIN_EMAIL:}") String bootstrapAdmin,
                             com.nebdev.devopsagent.saas.credential.CredentialService vault) {
        this.vault = vault;
        this.properties = properties;
        this.users = users;
        this.staff = staff;
        this.rls = rls;
        this.bootstrapAdmin = bootstrapAdmin;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.getRunner().isEmbedded() && properties.getRunner().getToken().length() < 32) {
            throw new IllegalStateException("AUTOPILOT_RUNNER_TOKEN doit faire au moins 32 caractères en mode remote.");
        }
        String runnerKey = properties.getRunner().getPublicKey();
        boolean hasRunnerKey = runnerKey != null && !runnerKey.isBlank();
        if (!properties.getRunner().isEmbedded() && !hasRunnerKey) {
            throw new IllegalStateException("Mode remote : AUTOPILOT_RUNNER_PUBLIC_KEY (PEM RSA ≥ 3072 bits) est obligatoire "
                    + "— le control plane scelle les clés SSH pour le runner (ADR-055).");
        }
        if (properties.getRunner().isEmbedded() && hasRunnerKey) {
            throw new IllegalStateException("AUTOPILOT_RUNNER_PUBLIC_KEY n'a de sens qu'avec AUTOPILOT_RUNNER_MODE=remote : "
                    + "en embedded, une clé scellée ne pourrait plus être lue.");
        }
        Object superuser = em.createNativeQuery("select rolsuper or rolbypassrls from pg_roles where rolname = current_user")
                .getSingleResult();
        if (Boolean.TRUE.equals(superuser)) {
            throw new IllegalStateException("Le rôle de connexion de l'application contourne la RLS (superuser ou "
                    + "BYPASSRLS). Utiliser AUTOPILOT_DB_USER = rôle applicatif dédié (ADR-049).");
        }
        if (bootstrapAdmin != null && !bootstrapAdmin.isBlank() && staff.count() == 0) {
            users.findByEmail(bootstrapAdmin.strip()).ifPresentOrElse(u -> {
                staff.save(new PlatformStaff(u.getId(), "PLATFORM_ADMIN"));
                log.warn("Premier administrateur plateforme : {}", u.getEmail());
            }, () -> log.warn("AUTOPILOT_BOOTSTRAP_ADMIN_EMAIL={} : compte introuvable (s'inscrire d'abord).", bootstrapAdmin));
        }
        rls.enterPlatform();
        if (vault.sealsForRunner()) {
            vault.resealAll();
        }
    }
}
