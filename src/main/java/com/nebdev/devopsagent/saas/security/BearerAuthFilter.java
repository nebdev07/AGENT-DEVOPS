package com.nebdev.devopsagent.saas.security;

import com.nebdev.devopsagent.saas.identity.AuthService;

import com.nebdev.devopsagent.saas.apitoken.ApiTokenService;
import com.nebdev.devopsagent.saas.shared.Actor;
import com.nebdev.devopsagent.saas.shared.Hashing;
import com.nebdev.devopsagent.saas.shared.SaasProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code Authorization: Bearer <jeton>} : session ({@code ses_…}), jeton d'API ({@code apt_…}) ou runner
 * ({@code AUTOPILOT_RUNNER_TOKEN}, API interne seulement). Pas de cookie : pas de CSRF possible.
 */
public class BearerAuthFilter extends OncePerRequestFilter {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_TOKEN = "ROLE_API_TOKEN";
    public static final String ROLE_STAFF = "ROLE_STAFF";
    public static final String ROLE_PLATFORM_ADMIN = "ROLE_PLATFORM_ADMIN";
    public static final String ROLE_RUNNER = "ROLE_RUNNER";

    private final AuthService auth;
    private final ApiTokenService apiTokens;
    private final SaasProperties properties;

    public BearerAuthFilter(AuthService auth, ApiTokenService apiTokens, SaasProperties properties) {
        this.auth = auth;
        this.apiTokens = apiTokens;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).strip();
            resolve(token, request.getRequestURI()).ifPresent(actor -> {
                SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));
                if (actor.locale() != null && request.getHeader("Accept-Language") == null) {
                    LocaleContextHolder.setLocale(Locale.forLanguageTag(actor.locale()));
                }
            });
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<Actor> resolve(String token, String path) {
        if (token.startsWith(AuthService.SESSION_PREFIX + "_")) {
            return auth.authenticate(token);
        }
        if (token.startsWith(ApiTokenService.PREFIX + "_")) {
            return apiTokens.authenticate(token);
        }
        String runnerToken = properties.getRunner().getToken();
        if (path.startsWith("/api/v2/internal/") && runnerToken != null && runnerToken.length() >= 32
                && Hashing.constantTimeEquals(token, runnerToken)) {
            return Optional.of(new Actor(Actor.Type.RUNNER, null, null, null, null, null, Set.of(), "en"));
        }
        return Optional.empty();
    }

    static final class ActorAuthentication extends AbstractAuthenticationToken {
        private final Actor actor;

        ActorAuthentication(Actor actor) {
            super(authorities(actor));
            this.actor = actor;
            setAuthenticated(true);
        }

        private static List<SimpleGrantedAuthority> authorities(Actor actor) {
            List<SimpleGrantedAuthority> out = new ArrayList<>();
            switch (actor.type()) {
                case USER -> {
                    out.add(new SimpleGrantedAuthority(ROLE_USER));
                    if (actor.isStaff()) {
                        out.add(new SimpleGrantedAuthority(ROLE_STAFF));
                    }
                    if (actor.isPlatformAdmin()) {
                        out.add(new SimpleGrantedAuthority(ROLE_PLATFORM_ADMIN));
                    }
                }
                case API_TOKEN -> out.add(new SimpleGrantedAuthority(ROLE_TOKEN));
                case RUNNER -> out.add(new SimpleGrantedAuthority(ROLE_RUNNER));
            }
            return out;
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return actor;
        }
    }
}
