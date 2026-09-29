package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformSpecTest {

    @Test
    void derivesSubdomainFromBase() {
        PlatformSpec p = PlatformSpec.normalized(
                "ma-asso",
                "apps.exemple.com",
                "host",
                List.of(svc("api", "https://github.com/org/api.git", "", 8080, List.of("mariadb", "redis"))),
                null
        );
        assertThat(p.routing()).isEqualTo("host");
        assertThat(p.services()).hasSize(1);
        assertThat(p.services().get(0).hostname()).isEqualTo("api.ma-asso.apps.exemple.com");
        assertThat(p.services().get(0).needs()).containsExactly("mariadb", "redis");
    }

    @Test
    void sslipWhenNoBaseButIp() {
        PlatformSpec p = PlatformSpec.normalized(
                "lab",
                "",
                "host",
                List.of(svc("www", "https://github.com/org/site.git", "", 3000, List.of())),
                "10.0.0.8"
        );
        assertThat(p.services().get(0).hostname()).isEqualTo("www-lab.10.0.0.8.sslip.io");
    }

    @Test
    void pathRoutingSharesHostAndDerivesPrefixes() {
        PlatformSpec p = PlatformSpec.normalized(
                "ma-asso",
                "apps.exemple.com",
                "path",
                List.of(
                        svc("api", "https://github.com/org/api.git", "", 8080, List.of()),
                        svc("www", "https://github.com/org/site.git", "", 3000, List.of())
                ),
                null
        );
        assertThat(p.pathRouting()).isTrue();
        assertThat(p.services().get(0).hostname()).isEqualTo("ma-asso.apps.exemple.com");
        assertThat(p.services().get(1).hostname()).isEqualTo("ma-asso.apps.exemple.com");
        assertThat(p.services().get(0).pathPrefix()).isEqualTo("/api");
        assertThat(p.services().get(1).pathPrefix()).isEqualTo("/");
    }

    @Test
    void pathRoutingRejectsDuplicatePrefixes() {
        PlatformServiceSpec a = new PlatformServiceSpec(
                "api", "https://github.com/org/api.git", "main", "", 8080, "", List.of(), Map.of(), "/api");
        PlatformServiceSpec b = new PlatformServiceSpec(
                "admin", "https://github.com/org/admin.git", "main", "", 3000, "", List.of(), Map.of(), "/api");
        assertThatThrownBy(() -> PlatformSpec.normalized("x", "apps.exemple.com", "path", List.of(a, b), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deux fois");
    }

    @Test
    void rejectsSshGit() {
        assertThatThrownBy(() -> PlatformSpec.normalized(
                "x",
                "apps.exemple.com",
                "host",
                List.of(svc("api", "git@github.com:org/api.git", "", 8080, List.of())),
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void storesBuildArg() {
        PlatformServiceSpec raw = new PlatformServiceSpec(
                "www",
                "https://github.com/org/site.git",
                "main",
                "",
                3000,
                "",
                List.of(),
                Map.of("NEXT_PUBLIC_API_URL", "https://api.ma-asso.apps.exemple.com"),
                ""
        );
        PlatformSpec p = PlatformSpec.normalized("ma-asso", "apps.exemple.com", "host", List.of(raw), null);
        assertThat(p.services().get(0).buildArgs()).containsEntry(
                "NEXT_PUBLIC_API_URL", "https://api.ma-asso.apps.exemple.com");
    }

    private static PlatformServiceSpec svc(String id, String git, String host, int port, List<String> needs) {
        return new PlatformServiceSpec(id, git, "main", host, port, "", needs, Map.of(), "");
    }
}
