package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void platformBuildFailureNamesApiAndSingleRedeploy() {
        PlatformSpec platform = arcLike();
        String raw = """
                CLONED=www@d1556fa
                CLONED=backoffice@fac3bfa
                CLONED=api@db1e189
                BUILD_FAILED
                BUILD_FAILED_SERVICE=api
                #29 [api build 4/5] RUN mvn -B -q -DskipTests package
                #29 172.0 [ERROR] /src/src/main/java/org/nwanyo/arc/dao/repository/MediasRepository.java:[245,142] cannot find symbol
                #29 172.0   symbol:   method getIsDeletedParam()
                #29 172.0   location: variable dto of type org.nwanyo.arc.utils.dto.MediasDto
                #29 ERROR: process "/bin/sh -c mvn -B -q -DskipTests package" did not complete successfully: exit code: 1
                > [api build 4/5] RUN mvn -B -q -DskipTests package:
                failed to solve: process "/bin/sh -c mvn -B -q -DskipTests package" did not complete successfully: exit code: 1
                """;
        assertThatThrownBy(() -> AppRemoteFailure.throwIfKnownPlatform(raw, platform, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service « api »")
                .hasMessageContaining("Maven / Java")
                .hasMessageContaining("MediasRepository.java:245")
                .hasMessageContaining("getIsDeletedParam")
                .hasMessageContaining("absente de « MediasDto »")
                .hasMessageContaining("Déployer api")
                .hasMessageContaining("ne clique pas « Tout déployer »")
                .hasMessageContaining("arc-backend");
    }

    @Test
    void platformGitClonePermissionDeniedNamesRepoAndFix() {
        PlatformSpec platform = arcLike();
        String raw = """
                GIT_CLONE_FAILED
                SERVICE=www
                GIT_URL=git@github.com:nebdev07/arc-client.git
                GIT_REF=main
                fatal: could not create leading directories of '/opt/vps-autopilot/platforms/arc/src/www': Permission denied
                """;
        assertThatThrownBy(() -> AppRemoteFailure.throwIfKnownPlatform(raw, platform, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arc-client")
                .hasMessageContaining("droits insuffisants")
                .hasMessageContaining("www")
                .hasMessageContaining("chown deploy:deploy")
                .hasMessageNotContaining("« dépôt »");
    }

    @Test
    void resolveFailedServiceFromBuildkitSummary() {
        String raw = """
                #28 [www builder 5/5] RUN npm run build
                #29 [api build 4/5] RUN mvn
                > [api build 4/5] RUN mvn
                failed to solve: exit code: 1
                BUILD_FAILED
                """;
        assertThat(AppRemoteFailure.resolveFailedService(raw, Set.of("www", "api", "backoffice"), null))
                .isEqualTo("api");
    }

    @Test
    void preflightDetectsMissingDockerfileOnRepoWithoutOne() {
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

    private static PlatformSpec arcLike() {
        return new PlatformSpec(
                "arc",
                "arc-nwanyo.org",
                PlatformSpec.ROUTING_PATH,
                List.of(
                        new PlatformServiceSpec("www", "git@github.com:nebdev07/arc-client.git", "main",
                                "arc-nwanyo.org", 3000, "", List.of(), Map.of(), "/"),
                        new PlatformServiceSpec("backoffice", "git@github.com:nebdev07/arc-backoffice.git", "main",
                                "arc-nwanyo.org", 3000, "", List.of(), Map.of(), "/arc-backoffice"),
                        new PlatformServiceSpec("api", "git@github.com:nebdev07/arc-backend.git", "main",
                                "arc-nwanyo.org", 8080, "", List.of("mariadb"), Map.of(), "/arc-api")
                )
        );
    }
}
