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
                        Map.of()
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
}
