package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    @TempDir
    Path tmp;

    AuthService auth;
    AccountStore accounts;

    @BeforeEach
    void setUp() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(tmp.toString());
        ObjectMapper mapper = new ObjectMapper();
        RoleStore roles = new RoleStore(props, mapper);
        accounts = new AccountStore(props, mapper);
        MagicTokenStore tokens = new MagicTokenStore(props, mapper);
        SessionStore sessions = new SessionStore(props, mapper);
        GrantStore grants = new GrantStore(props, mapper);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("platform");
        AccessControl access = new AccessControl(roles, grants, env);
        PlatformProperties platform = new PlatformProperties();
        platform.setMailDevCapture(true);
        platform.setPublicBaseUrl("http://test.local");
        auth = new AuthService(accounts, tokens, sessions, access, platform);
        roles.list();
        accounts.create("admin@test.local", "Admin", RoleStore.SUPER_ADMIN);
    }

    @Test
    void magicLinkCreatesSession() throws Exception {
        Map<String, Object> req = auth.requestMagicLink("admin@test.local");
        assertThat(req.get("devToken")).isNotNull();
        var session = auth.consumeMagicLink(req.get("devToken").toString());
        var principal = auth.principalForSession(session.id());
        assertThat(principal.email()).isEqualTo("admin@test.local");
        assertThat(principal.isPlatformAdmin()).isTrue();

        assertThatThrownBy(() -> auth.consumeMagicLink(req.get("devToken").toString()))
                .isInstanceOf(PlatformAccessException.class);
    }

    @Test
    void unknownEmailDoesNotLeak() throws Exception {
        Map<String, Object> req = auth.requestMagicLink("inconnu@test.local");
        assertThat(req.get("status")).isEqualTo("ok");
        assertThat(req.containsKey("devToken")).isFalse();
    }
}
