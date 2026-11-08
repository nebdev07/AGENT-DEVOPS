package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformDeployPlanServiceResolveTest {

    @Test
    void blankMeansFullPlatform() {
        PlatformSpec p = sample();
        assertThat(PlatformDeployPlanService.resolveOnly(p, null)).isNull();
        assertThat(PlatformDeployPlanService.resolveOnly(p, "  ")).isNull();
    }

    @Test
    void findsService() {
        PlatformSpec p = sample();
        assertThat(PlatformDeployPlanService.resolveOnly(p, "API").id()).isEqualTo("api");
    }

    @Test
    void unknownServiceFails() {
        assertThatThrownBy(() -> PlatformDeployPlanService.resolveOnly(sample(), "ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    private static PlatformSpec sample() {
        return new PlatformSpec(
                "arc",
                "example.com",
                PlatformSpec.ROUTING_PATH,
                List.of(
                        new PlatformServiceSpec("www", "https://example.com/www.git", "main",
                                "example.com", 3000, "", List.of(), Map.of(), "/"),
                        new PlatformServiceSpec("api", "https://example.com/api.git", "main",
                                "example.com", 8080, "", List.of("mariadb"), Map.of(), "/api")
                )
        );
    }
}
