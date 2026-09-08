package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DockerfileCargoTest {

    @Test
    void rootDockerfileIsCargoOnly() throws Exception {
        Path file = Path.of("Dockerfile");
        assertThat(file).exists();
        String body = Files.readString(file);
        assertThat(body).contains("SPRING_PROFILES_ACTIVE=cargo");
        assertThat(body).contains("8080");
        assertThat(body).doesNotContain("8088:8088");
    }
}
