package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformComposeTest {

    @Test
    void composeUsesSubdomainsAndDataNetworkWithoutPublishedPorts() {
        PlatformSpec p = PlatformSpec.normalized(
                "ma-asso",
                "apps.exemple.com",
                "host",
                List.of(new PlatformServiceSpec(
                        "api",
                        "https://github.com/org/api.git",
                        "main",
                        "",
                        8080,
                        "backend/api",
                        List.of("mariadb", "volume:uploads"),
                        Map.of(),
                        ""
                )),
                null
        );
        String yaml = PlatformCompose.yaml(p);
        assertThat(yaml).contains("context: ./src/api/backend/api");
        assertThat(yaml).contains("Host(`api.ma-asso.apps.exemple.com`)");
        assertThat(yaml).contains("plat_ma_asso_uploads:/app/data");
        assertThat(yaml).doesNotContain("8080:8080");
        assertThat(yaml).contains("external: true");
        assertThat(PlatformCompose.manifest(p)).contains("backend/api");
    }

    @Test
    void pathRoutingUsesPathPrefixAndStripWithoutPublishedPorts() {
        PlatformSpec p = PlatformSpec.normalized(
                "ma-asso",
                "apps.exemple.com",
                "path",
                List.of(
                        new PlatformServiceSpec(
                                "api",
                                "https://github.com/org/api.git",
                                "main",
                                "",
                                8080,
                                "",
                                List.of(),
                                Map.of(),
                                ""
                        ),
                        new PlatformServiceSpec(
                                "www",
                                "https://github.com/org/site.git",
                                "main",
                                "",
                                3000,
                                "",
                                List.of(),
                                Map.of(),
                                ""
                        )
                ),
                null
        );
        String yaml = PlatformCompose.yaml(p);
        assertThat(yaml).contains("Host(`ma-asso.apps.exemple.com`) && PathPrefix(`/api`)");
        assertThat(yaml).contains("stripprefix.prefixes=/api");
        assertThat(yaml).contains("Host(`ma-asso.apps.exemple.com`)");
        assertThat(yaml).doesNotContain("PathPrefix(`/`)");
        assertThat(yaml).doesNotContain("8080:8080");
        assertThat(PlatformCompose.publicUrl(p, p.services().get(0)))
                .isEqualTo("https://ma-asso.apps.exemple.com/api/");
        assertThat(PlatformCompose.publicUrl(p, p.services().get(1)))
                .isEqualTo("https://ma-asso.apps.exemple.com/");
    }

    @Test
    void httpOnlyServerGetsPlainRoutersNestedPathAndTranslatedPort() {
        PlatformSpec p = PlatformSpec.normalized(
                "app",
                "",
                "path",
                List.of(
                        new PlatformServiceSpec("web", "https://github.com/org/web.git", "main", "203.0.113.9",
                                80, "", List.of(), Map.of(), "/app"),
                        new PlatformServiceSpec("api", "https://github.com/org/api.git", "main", "203.0.113.9",
                                8080, "", List.of(), Map.of(), "/app/api")
                ),
                null
        );
        ServerProfile natHttp = new ServerProfile(true, 91, true);
        String yaml = PlatformCompose.yaml(p, natHttp);
        assertThat(yaml).contains("rule=PathPrefix(`/app/api`)\"").doesNotContain("Host(");
        assertThat(yaml).contains(".entrypoints=web");
        assertThat(yaml).doesNotContain("websecure").doesNotContain("certresolver").doesNotContain("-secure");
        assertThat(PlatformCompose.publicUrl(p, p.services().get(1), natHttp))
                .isEqualTo("http://203.0.113.9:91/app/api/");
        assertThat(PlatformCompose.yaml(p)).contains("websecure");
    }
}
