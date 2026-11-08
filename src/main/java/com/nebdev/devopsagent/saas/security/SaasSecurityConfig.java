package com.nebdev.devopsagent.saas.security;

import com.nebdev.devopsagent.saas.identity.AuthService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.saas.apitoken.ApiTokenService;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sécurité HTTP du SaaS. Règles grossières ici (qui peut appeler quelle famille d'API) ; l'autorisation fine
 * (organisation + permission) est faite dans les cas d'usage par {@code OrgAccess} — jamais par l'interface.
 *
 * <ul>
 *   <li>{@code /api/v2/public/**} : anonyme (inscription, connexion, plans, webhooks signés).</li>
 *   <li>{@code /api/v2/admin/**} : personnel INOVNE.</li>
 *   <li>{@code /api/v2/internal/**} : runner d'exécution.</li>
 *   <li>{@code /api/v2/**} : utilisateur ou jeton d'API.</li>
 *   <li>{@code /api/v1/**} : <b>fermé</b> (API de la console locale, sans authentification).</li>
 * </ul>
 */
@Configuration
public class SaasSecurityConfig {

    @Bean
    SecurityFilterChain saasChain(HttpSecurity http, AuthService auth, ApiTokenService tokens,
                                  SaasProperties properties, ObjectMapper mapper) throws Exception {
        http.csrf(c -> c.disable())
                .cors(c -> c.configurationSource(cors(properties)))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .anonymous(a -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(c -> {})
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(t -> t.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(c -> c.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                        + "script-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
                .addFilterBefore(new BearerAuthFilter(auth, tokens, properties), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> error(res, mapper, 401, "auth_required"))
                        .accessDeniedHandler((req, res, ex) -> error(res, mapper, 403, "forbidden")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/v2/public/**", "/api/v2/openapi/**", "/api/v2/docs/**",
                                "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/api/v1/**").denyAll()
                        .requestMatchers("/api/v2/internal/**").hasAuthority(BearerAuthFilter.ROLE_RUNNER)
                        .requestMatchers("/api/v2/admin/**").hasAuthority(BearerAuthFilter.ROLE_STAFF)
                        .requestMatchers("/api/v2/**").hasAnyAuthority(BearerAuthFilter.ROLE_USER, BearerAuthFilter.ROLE_TOKEN)
                        .requestMatchers("/actuator/**").denyAll()
                        // Application Angular (fichiers statiques) : publique, elle n'a aucun secret.
                        .anyRequest().permitAll());
        return http.build();
    }

    private static UrlBasedCorsConfigurationSource cors(SaasProperties properties) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(properties.getPublicUrl().replaceAll("/$", "")));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language", "Idempotency-Key",
                "X-Correlation-Id"));
        c.setExposedHeaders(List.of("X-Correlation-Id"));
        c.setAllowCredentials(false);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/v2/**", c);
        return src;
    }

    private static void error(jakarta.servlet.http.HttpServletResponse res, ObjectMapper mapper, int status,
                              String code) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", code);
        body.put("status", status);
        res.getWriter().write(mapper.writeValueAsString(body));
    }
}
