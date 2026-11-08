package com.nebdev.devopsagent.config;

import com.nebdev.devopsagent.saas.shared.SaasProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * Module SaaS (ADR-047) : chargé uniquement en profil {@code saas}. Le reste de l'application (moteur
 * d'exécution, console locale) ne dépend jamais de ce module.
 */
@Configuration
@Profile("saas")
@ComponentScan(basePackages = "com.nebdev.devopsagent.saas",
        nameGenerator = org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator.class)
@EnableJpaRepositories(basePackages = "com.nebdev.devopsagent.saas", considerNestedRepositories = true)
@EntityScan("com.nebdev.devopsagent.saas")
@EnableScheduling
@EnableConfigurationProperties(SaasProperties.class)
public class SaasModuleConfig {

    private final Environment environment;

    public SaasModuleConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void refuseIncompatibleProfiles() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains("platform") || active.contains("cargo") || active.contains("runner")) {
            throw new IllegalStateException(
                    "Le profil saas ne se combine ni avec platform, ni cargo, ni runner (profils actifs : " + active + ").");
        }
    }

    @Bean
    Clock saasClock() {
        return Clock.systemUTC();
    }
}
