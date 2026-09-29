package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitUrlsTest {

    @Test
    void acceptsHttpsAndGithubSsh() {
        assertThat(GitUrls.normalize("https://github.com/org/app.git"))
                .isEqualTo("https://github.com/org/app.git");
        assertThat(GitUrls.normalize("git@github.com:org/app"))
                .isEqualTo("git@github.com:org/app.git");
        assertThat(GitUrls.ssh("git@gitlab.com:group/app.git")).isTrue();
        assertThat(GitUrls.ssh("https://github.com/org/app.git")).isFalse();
    }

    @Test
    void rejectsCredentialsInHttps() {
        assertThatThrownBy(() -> GitUrls.normalize("https://user:ghp_secret@github.com/org/app.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mot de passe");
    }

    @Test
    void rejectsUnknownSshHost() {
        assertThatThrownBy(() -> GitUrls.normalize("git@bitbucket.org:org/app.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("github.com");
    }
}
