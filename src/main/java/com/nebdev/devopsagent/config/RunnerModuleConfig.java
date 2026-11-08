package com.nebdev.devopsagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.saas.engine.LocalEngineAdapter;
import com.nebdev.devopsagent.saas.runner.RunnerAgent;
import com.nebdev.devopsagent.saas.runner.RunnerAgentProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

/**
 * Runner séparé (profil {@code runner}, ADR-055) : moteur d'exécution + client de l'API interne du control plane.
 * Ni base de données, ni serveur web, ni module SaaS métier : seulement l'adaptateur moteur et l'agent.
 */
@Configuration
@Profile("runner")
@Import(LocalEngineAdapter.class)
@EnableConfigurationProperties(RunnerAgentProperties.class)
public class RunnerModuleConfig {

    private final Environment environment;

    public RunnerModuleConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void refuseIncompatibleProfiles() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains("saas") || active.contains("platform") || active.contains("cargo")) {
            throw new IllegalStateException("Le profil runner s'utilise seul (profils actifs : " + active + ").");
        }
    }

    @Bean
    RunnerAgent runnerAgent(RunnerAgentProperties properties, LocalEngineAdapter engine, ObjectMapper mapper) {
        return new RunnerAgent(properties, engine, mapper);
    }
}
