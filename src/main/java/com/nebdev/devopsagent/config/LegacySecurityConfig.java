package com.nebdev.devopsagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Profils historiques (console locale, cargo, platform) : Spring Security est présent sur le classpath pour le
 * SaaS (ADR-047), mais n’ajoute <b>aucune</b> règle ici. Le comportement reste celui d’avant :
 * console locale ouverte sur 127.0.0.1 (ADR-045), {@code CargoModeFilter}, {@code PlatformAuthFilter} +
 * {@code PlatformRbacFilter} (ADR-046). Seuls les en-têtes de sécurité HTTP par défaut sont ajoutés.
 */
@Configuration
@Profile("!saas & !runner")
public class LegacySecurityConfig {

    @Bean
    SecurityFilterChain legacyChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
