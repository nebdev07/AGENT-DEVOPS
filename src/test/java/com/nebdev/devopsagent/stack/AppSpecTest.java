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
    void acceptsGithubSshGit() {
        AppSpec spec = AppSpec.normalized("x", "git@github.com:org/app.git", "main", "app.example.com", 80);
        assertThat(spec.gitUrl()).isEqualTo("git@github.com:org/app.git");
        assertThat(GitUrls.ssh(spec.gitUrl())).isTrue();
    }

    @Test
    void underscoreInIdBecomesHyphen() {
        AppSpec spec = AppSpec.normalized(
                "mon_app",
                "https://github.com/organisation/mon-app.git",
                "main",
                "mon-app.198.51.100.20.sslip.io",
                8080
        );
        assertThat(spec.id()).isEqualTo("mon-app");
    }

    @Test
    void rejectsGitUrlAsHostname() {
        assertThatThrownBy(() -> AppSpec.normalized(
                "vps-autopilot",
                "https://github.com/organisation/mon-agent.git",
                "main",
                "https://github.com/organisation/mon-agent",
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
                "mon-app.apps.mondomaine.com",
                "https://github.com/org/app.git",
                "main",
                "mon-app.apps.mondomaine.com",
                3000
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cause :")
                .hasMessageContaining("nom d’hôte")
                .hasMessageContaining("Identifiant")
                .hasMessageContaining("Nom d’hôte");
    }
}
