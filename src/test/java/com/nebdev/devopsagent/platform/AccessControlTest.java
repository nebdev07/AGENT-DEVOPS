package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessControlTest {

    @TempDir
    Path tmp;

    RoleStore roles;
    AccountStore accounts;
    GrantStore grants;
    AccessControl access;

    @BeforeEach
    void setUp() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(tmp.toString());
        ObjectMapper mapper = new ObjectMapper();
        roles = new RoleStore(props, mapper);
        accounts = new AccountStore(props, mapper);
        grants = new GrantStore(props, mapper);
        Environment env = new MockEnvironment().withProperty("spring.profiles.active", "platform");
        ((MockEnvironment) env).setActiveProfiles("platform");
        access = new AccessControl(roles, grants, env);
        roles.list();
    }

    @Test
    void operatorCannotTouchUnassignedServer() throws Exception {
        var op = accounts.create("ops@exemple.com", "Ops", RoleStore.OPERATOR);
        var principal = access.loadPrincipal(op);
        bind(principal);

        assertThatThrownBy(() -> access.requireServer("10.0.0.1", 22, "ubuntu", Permission.SERVER_ANALYZE))
                .isInstanceOf(PlatformAccessException.class)
                .hasMessageContaining("pas assigné");

        grants.assign(op.id(), "10.0.0.1", 22, "ubuntu", "lab");
        assertThat(access.requireServer("10.0.0.1", 22, "ubuntu", Permission.SERVER_ANALYZE).email())
                .isEqualTo("ops@exemple.com");
    }

    @Test
    void platformAdminBypassesGrants() throws Exception {
        var admin = accounts.create("admin@exemple.com", "Admin", RoleStore.SUPER_ADMIN);
        bind(access.loadPrincipal(admin));
        assertThat(access.requireServer("any.host", 22, "root", Permission.SERVER_CHANGE_DEPLOY).isPlatformAdmin())
                .isTrue();
    }

    @Test
    void rolePermissionsAreEditable() throws Exception {
        roles.updatePermissions(RoleStore.VIEWER, List.of(Permission.SERVER_READ.code()));
        assertThat(roles.require(RoleStore.VIEWER).permissions())
                .containsExactly(Permission.SERVER_READ.code());
        assertThatThrownBy(() -> roles.updatePermissions(RoleStore.SUPER_ADMIN, List.of(Permission.SERVER_READ.code())))
                .hasMessageContaining("platform.admin");
    }

    @Test
    void viewerLacksDeployPermission() throws Exception {
        var viewer = accounts.create("see@exemple.com", "See", RoleStore.VIEWER);
        bind(access.loadPrincipal(viewer));
        grants.assign(viewer.id(), "10.0.0.2", 22, "ubuntu", "");
        assertThatThrownBy(() -> access.requireServer("10.0.0.2", 22, "ubuntu", Permission.SERVER_CHANGE_DEPLOY))
                .hasMessageContaining("Permission manquante");
    }

    private static void bind(PlatformPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AccessControl.bind(principal, "test-session");
    }
}
