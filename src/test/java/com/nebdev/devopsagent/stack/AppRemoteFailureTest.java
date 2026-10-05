package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AppRemoteFailureTest {

    private static final AppSpec APP = AppSpec.normalized(
            "mon-app",
            "https://github.com/organisation/mon-app.git",
            "main",
            "mon-app.example.com",
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
    void preflightDetectsMissingDockerfileOnRepoWithoutOne() {
        // Dépôt public figé sans Dockerfile (ignoré si GitHub est injoignable).
        AppSpec noDockerfile = AppSpec.normalized(
                "hello",
                "https://github.com/octocat/Hello-World.git",
                "master",
                "hello.example.com",
                8080
        );
        assumeTrue(DockerfilePreflight.probe(noDockerfile).isPresent(), "GitHub injoignable depuis ce poste");
        assertThat(DockerfilePreflight.probe(noDockerfile)).contains(false);
    }
}
