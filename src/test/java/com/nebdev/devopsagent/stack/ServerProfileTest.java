package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerProfileTest {

    @Test
    void sharedServerNeverHardensSshLogins() {
        ServerProfile shared = new ServerProfile(false, 0, true);
        assertThat(shared.roleDetails("security", List.of()))
                .containsExactly("ufw", "fail2ban", "unattended");
        assertThat(shared.roleDetails("security", List.of("ufw", "ssh-harden"))).containsExactly("ufw");
        assertThat(ServerProfile.DEFAULT.roleDetails("security", List.of())).isEmpty();
    }

    @Test
    void httpOnlyTraefikScriptHasNoHttps() {
        ServerProfile http = new ServerProfile(true, 91, false);
        List<String> details = http.roleDetails("traefik", List.of());
        String script = RoleScript.load("traefik", details);
        assertThat(script).contains("APPLY_PROXY=1\n").contains("APPLY_HTTPS=0\n");
        assertThat(RoleScript.load("traefik", List.of())).contains("APPLY_HTTPS=1\n");
        assertThat(RoleScript.load("traefik", List.of("proxy"))).contains("APPLY_HTTPS=1\n");
    }

    @Test
    void authorityAddsOnlyNonStandardPorts() {
        assertThat(new ServerProfile(true, 91, false).authority("h")).isEqualTo("h:91");
        assertThat(new ServerProfile(true, 80, false).authority("h")).isEqualTo("h");
        assertThat(new ServerProfile(false, 0, false).authority("h")).isEqualTo("h");
        assertThat(new ServerProfile(true, 0, false).scheme()).isEqualTo("http");
        assertThatThrownBy(() -> new ServerProfile(false, 70000, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
