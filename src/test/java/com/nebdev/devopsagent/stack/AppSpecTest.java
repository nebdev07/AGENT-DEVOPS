package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppSpecTest {

    @Test
    void acceptsPublicHttpsGit() {
        AppSpec spec = AppSpec.normalized("mon-app", "https://github.com/org/app.git", "main", "app.example.com", 8080);
        assertThat(spec.id()).isEqualTo("mon-app");
        assertThat(spec.port()).isEqualTo(8080);
    }

    @Test
    void rejectsSshGit() {
        assertThatThrownBy(() -> AppSpec.normalized("x", "git@github.com:org/app.git", "main", "app.example.com", 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cause :")
                .hasMessageContaining("Où :")
                .hasMessageContaining("À faire :")
                .hasMessageContaining("HTTPS");
    }

    @Test
    void underscoreInIdBecomesHyphen() {
        AppSpec spec = AppSpec.normalized(
                "my_bourse",
                "https://github.com/nebdev07/BOURSE.git",
                "main",
                "my-bourse.57.128.186.38.sslip.io",
                8080
        );
        assertThat(spec.id()).isEqualTo("my-bourse");
    }

    @Test
    void rejectsGitUrlAsHostname() {
        assertThatThrownBy(() -> AppSpec.normalized(
                "vps-autopilot",
                "https://github.com/nebdev07/AGENT-DEVOPS.git",
                "main",
                "https://github.com/nebdev07/AGENT-DEVOPS",
                8080
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cause :")
                .hasMessageContaining("Nom d’hôte")
                .hasMessageContaining("URL");
    }

    @Test
    void emptyIdExplainsCauseWhereAction() {
        assertThatThrownBy(() -> AppSpec.normalized(
                "  ",
                "https://github.com/org/app.git",
                "main",
                "app.example.com",
                8080
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cause :")
                .hasMessageContaining("Où :")
                .hasMessageContaining("À faire :")
                .hasMessageContaining("Identifiant");
    }

    @Test
    void fqdnAsIdExplainsHostnameConfusion() {
        assertThatThrownBy(() -> AppSpec.normalized(
                "bourse.apps.nebdev.org",
                "https://github.com/org/app.git",
                "main",
                "bourse.apps.nebdev.org",
                3000
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cause :")
                .hasMessageContaining("nom d’hôte")
                .hasMessageContaining("Identifiant")
                .hasMessageContaining("Nom d’hôte");
    }
}
