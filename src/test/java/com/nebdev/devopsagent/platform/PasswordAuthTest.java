package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordAuthTest {

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
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("platform");
        AccessControl access = new AccessControl(roles, grants(props, mapper), env);
        PlatformProperties platform = new PlatformProperties();
        platform.setDefaultPassword("VpsAutopilot!ChangeMe");
        platform.setMailDevCapture(true); // mode local : reset usine autorisé
        auth = new AuthService(accounts, tokens, sessions, access, platform);
        roles.list();
        accounts.create("nebdev07@gmail.com", "Admin", RoleStore.SUPER_ADMIN, "VpsAutopilot!ChangeMe", true);
    }

    private static GrantStore grants(DevopsProperties props, ObjectMapper mapper) {
        return new GrantStore(props, mapper);
    }

    @Test
    void loginThenMustChangeThenAccess() throws Exception {
        var session = auth.loginWithPassword("nebdev07@gmail.com", "VpsAutopilot!ChangeMe");
        var principal = auth.principalForSession(session.id());
        assertThat(principal.mustChangePassword()).isTrue();

        assertThatThrownBy(() -> auth.loginWithPassword("nebdev07@gmail.com", "wrong-password-xx"))
                .isInstanceOf(PlatformAccessException.class);

        auth.changePassword(principal.accountId(), "VpsAutopilot!ChangeMe", "NouveauSecret!99");
        var again = auth.loginWithPassword("nebdev07@gmail.com", "NouveauSecret!99");
        assertThat(auth.principalForSession(again.id()).mustChangePassword()).isFalse();
    }

    @Test
    void forgotPasswordLocalThenResetToDefault() throws Exception {
        auth.changePassword(
                accounts.findByEmail("nebdev07@gmail.com").orElseThrow().id(),
                "VpsAutopilot!ChangeMe",
                "NouveauSecret!99");

        var req = auth.requestPasswordReset("nebdev07@gmail.com");
        assertThat(req.get("devToken")).isNotNull();
        assertThat(req.get("devLink").toString()).contains("/reset-password.html?token=");

        auth.completePasswordResetToDefault(req.get("devToken").toString());
        var session = auth.loginWithPassword("nebdev07@gmail.com", "VpsAutopilot!ChangeMe");
        assertThat(auth.principalForSession(session.id()).mustChangePassword()).isTrue();
    }

    @Test
    void forgotPasswordLocalThenSetNewPassword() throws Exception {
        var req = auth.requestPasswordReset("nebdev07@gmail.com");
        auth.completePasswordReset(req.get("devToken").toString(), "AutreSecret!42");
        var session = auth.loginWithPassword("nebdev07@gmail.com", "AutreSecret!42");
        assertThat(auth.principalForSession(session.id()).mustChangePassword()).isFalse();
    }
}
