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
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsShellInSlug() {
        assertThatThrownBy(() -> AppSpec.normalized("bad;rm", "https://example.com/a.git", "main", "a.example.com", 80))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
