package com.nebdev.devopsagent.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePermissionsTest {

    @Test
    void mapsCriticalRoutes() {
        assertThat(RoutePermissions.match("POST", "/api/v1/discover").orElseThrow().permission())
                .isEqualTo(Permission.SERVER_ANALYZE);
        assertThat(RoutePermissions.match("POST", "/api/v1/notify").orElseThrow())
                .satisfies(r -> {
                    assertThat(r.permission()).isEqualTo(Permission.SERVER_CHANGE_NOTIFY);
                    assertThat(r.serverScoped()).isTrue();
                });
        assertThat(RoutePermissions.match("PUT", "/api/v1/platform/roles/OPERATOR/permissions").orElseThrow().permission())
                .isEqualTo(Permission.ROLE_MANAGE);
        assertThat(RoutePermissions.match("GET", "/api/v1/platform/grants/mine")).isEmpty();
        assertThat(RoutePermissions.match("POST", "/api/v1/platform/auth/request")).isEmpty();
        assertThat(RoutePermissions.match("POST", "/api/v1/platform/auth/forgot-password")).isEmpty();
        assertThat(RoutePermissions.match("POST", "/api/v1/platform/auth/reset-password")).isEmpty();
    }
}
