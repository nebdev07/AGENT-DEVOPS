package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppRemoteFailureTest {

    private static final AppSpec APP = AppSpec.normalized(
            "my-bourse",
            "https://github.com/nebdev07/BOURSE.git",
            "main",
            "my-bourse.example.com",
            8080
    );

    @Test
    void mapsNeedDockerfileFromStdoutInIoMessage() {
        String raw = "Commande distante exit=1\n###STDOUT###\nNEED_DOCKERFILE\n###STDERR###\nCloning into '/opt/.../src'...";
        assertThatThrownBy(() -> AppRemoteFailure.throwIfKnown(raw, APP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cause :")
                .hasMessageContaining("Dockerfile");
    }

    @Test
    void unknownMapsNeedDockerfileBeforeGeneric() {
        IOException io = new IOException(
                "Commande distante exit=1\n###STDOUT###\nNEED_DOCKERFILE\nHINT: racine\n###STDERR###\nCloning into 'x'...");
        assertThatThrownBy(() -> {
            throw AppRemoteFailure.unknown(io, APP);
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dockerfile");
    }

    @Test
    void preflightDetectsMissingDockerfileOnBourse() {
        assertThat(DockerfilePreflight.probe(APP)).contains(false);
    }
}
