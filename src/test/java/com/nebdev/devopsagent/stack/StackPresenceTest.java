package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.discovery.DiscoveryParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StackPresenceTest {

    @Test
    void dockerDetailsIncludeComposeAndGroupWhenReported() {
        var snap = new DiscoveryParser().parse(
                """
                ###DOCKER###
                PRESENT
                Docker version 29.1.3, build 29.1.3
                Docker Compose version v2.29.7
                GROUPS=ubuntu adm docker
                """,
                "ssh:57.128.186.38",
                "57.128.186.38",
                null
        );
        var present = StackPresence.of(snap);
        assertThat(present.get("docker")).containsExactly("engine", "compose", "docker-group");
    }

    @Test
    void composeAbsentIsNotTreatedAsPresent() {
        var snap = new DiscoveryParser().parse(
                """
                ###DOCKER###
                PRESENT
                Docker version 29.1.3
                COMPOSE_ABSENT
                GROUPS=ubuntu adm
                """,
                "ssh:57.128.186.38",
                "57.128.186.38",
                null
        );
        var present = StackPresence.of(snap);
        assertThat(present.get("docker")).containsExactly("engine");
        assertThat(present.get("docker")).doesNotContain("compose", "docker-group");
    }
}
