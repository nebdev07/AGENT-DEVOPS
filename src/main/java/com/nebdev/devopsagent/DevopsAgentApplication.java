package com.nebdev.devopsagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Point d'entrée unique. Le module SaaS ({@code com.nebdev.devopsagent.saas}) n'est chargé qu'en profil
 * {@code saas} par {@link com.nebdev.devopsagent.config.SaasModuleConfig} (ADR-047) : la console locale,
 * cargo et platform ne voient ni base de données ni beans SaaS.
 */
@SpringBootApplication
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.nebdev\\.devopsagent\\.saas\\..*")
})
@ConfigurationPropertiesScan(basePackages = {
        "com.nebdev.devopsagent.config", "com.nebdev.devopsagent.platform", "com.nebdev.devopsagent.stack",
        "com.nebdev.devopsagent.ssh", "com.nebdev.devopsagent.journal", "com.nebdev.devopsagent.api"
})
public class DevopsAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevopsAgentApplication.class, args);
    }
}
