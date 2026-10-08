package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void aliasesRedirectPermanentlyToMainHostFromRootServiceOnly() {
        PlatformSpec p = PlatformSpec.normalized(
                "asso",
                "",
                "path",
                List.of(
                        new PlatformServiceSpec("api", "https://github.com/org/api.git", "main",
                                "asso.org", 8080, "", List.of(), Map.of(), "/api"),
                        new PlatformServiceSpec("www", "https://github.com/org/site.git", "main",
                                "asso.org", 3000, "", List.of(), Map.of(), "/")
                ),
                List.of(" WWW.asso.org ", "www.asso.org", "old.asso.org"),
                null
        );
        assertThat(p.aliases()).containsExactly("www.asso.org", "old.asso.org");
        assertThat(PlatformCompose.aliasCarrier(p).id()).isEqualTo("www");

        String yaml = PlatformCompose.yaml(p);
        assertThat(yaml).contains("\"traefik.http.routers.asso-alias-secure.rule=Host(`www.asso.org`) || Host(`old.asso.org`)\"");
        assertThat(yaml).contains("traefik.http.routers.asso-alias-secure.tls.certresolver=le");
        assertThat(yaml).contains("asso-alias-redirect.redirectregex.replacement=https://asso.org/$${1}");
        assertThat(yaml).contains("asso-alias-redirect.redirectregex.permanent=true");
        assertThat(yaml.indexOf("asso-alias")).isGreaterThan(yaml.indexOf("container_name: plat-asso-www"));

        String plain = PlatformCompose.yaml(p, new ServerProfile(true, 91, true));
        assertThat(plain).contains("replacement=http://asso.org:91/$${1}");
        assertThat(plain).doesNotContain("asso-alias-secure");
    }

    @Test
    void aliasesRejectedOutsidePathRoutingOrWhenInvalid() {
        PlatformServiceSpec www = new PlatformServiceSpec("www", "https://github.com/org/site.git", "main",
                "asso.org", 3000, "", List.of(), Map.of(), "/");
        assertThatThrownBy(() -> PlatformSpec.normalized("asso", "", "host", List.of(www),
                List.of("www.asso.org"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlatformSpec.normalized("asso", "", "path", List.of(www),
                List.of("1.2.3.4"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlatformSpec.normalized("asso", "", "path", List.of(www),
                List.of("asso.org"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PlatformCompose.yaml(PlatformSpec.normalized("asso", "", "path", List.of(www), null)))
                .doesNotContain("alias");
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
