package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.discovery.DiscoveryParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InstallPlanBuilderTest {

    @Test
    void blankFixtureMarksSecurityAndDockerReady() throws Exception {
        var snap = new DiscoveryParser().parse(
                new String(new ClassPathResource("fixtures/ubuntu-vps2-blank.txt")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                "ssh:203.0.113.10",
                "203.0.113.10",
                null
        );
        var plan = InstallPlanBuilder.build(snap, Set.of("security", "docker", "traefik", "nginx"));
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("security") && a.status() == InstallPlan.Status.READY);
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("docker") && a.status() == InstallPlan.Status.READY);
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("traefik") && a.status() == InstallPlan.Status.PLANNED);
        assertThat(plan.actions()).noneMatch(a -> a.id().equals("nginx"));
    }

    @Test
    void fail2banAloneDoesNotCompleteSecurityBaseline() throws Exception {
        var snap = new DiscoveryParser().parse(
                """
                ###FAIL2BAN###
                ACTIVE
                ###SERVICES###
                fail2ban.service
                """,
                "ssh:57.128.186.38",
                "57.128.186.38",
                null
        );
        var plan = InstallPlanBuilder.build(snap, Set.of("security"));
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("security") && a.status() == InstallPlan.Status.READY);
        assertThat(plan.actions())
                .anyMatch(a -> a.id().equals("security") && a.reason().contains("Baseline incomplet"));
    }

    @Test
    void persistedSnapshotWithFail2banButOpenSshStaysReady() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var snap = mapper.readValue(
                """
                {
                  "source": "ssh:57.128.186.38",
                  "host": "57.128.186.38",
                  "runningServices": ["fail2ban.service", "ssh.service"],
                  "firewall": { "engine": "ufw", "ufwStatus": "unknown", "listeningTcpPorts": [22] }
                }
                """,
                com.nebdev.devopsagent.discovery.model.ServerSnapshot.class
        );
        var plan = InstallPlanBuilder.build(snap, Set.of("security"));
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("security") && a.status() == InstallPlan.Status.READY);
    }

    @Test
    void completeBaselineSkipsSecurityInstall() throws Exception {
        var snap = new DiscoveryParser().parse(
                """
                ###UFW###
                ENGINE=ufw
                Status: active
                ###FAIL2BAN###
                ACTIVE
                ###SSHD###
                permitrootlogin no
                passwordauthentication no
                pubkeyauthentication yes
                port 22
                ###SERVICES###
                fail2ban.service
                ssh.service
                """,
                "ssh:57.128.186.38",
                "57.128.186.38",
                null
        );
        var plan = InstallPlanBuilder.build(snap, Set.of("security"));
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("security") && a.status() == InstallPlan.Status.SKIP);
    }

    @Test
    void traefikStaysPlannedUntilSecuritySkip() throws Exception {
        var snap = new DiscoveryParser().parse(
                """
                ###DOCKER###
                PRESENT
                Docker version 29.0.0
                ###TRAEFIK###
                ABSENT
                ###UFW###
                ENGINE=ufw
                Status: active
                ###FAIL2BAN###
                ACTIVE
                ###SSHD###
                permitrootlogin no
                passwordauthentication yes
                port 22
                """,
                "ssh:203.0.113.10",
                "203.0.113.10",
                null
        );
        var plan = InstallPlanBuilder.build(snap, Set.of("docker", "traefik"));
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("traefik") && a.status() == InstallPlan.Status.PLANNED);
        assertThat(plan.actions())
                .anyMatch(a -> a.id().equals("traefik") && a.reason().contains("sécurité"));
    }

    @Test
    void traefikReadyWhenSecuritySkipAndDockerPresent() throws Exception {
        var snap = new DiscoveryParser().parse(
                """
                ###DOCKER###
                PRESENT
                Docker version 29.0.0
                ###TRAEFIK###
                ABSENT
                ###UFW###
                ENGINE=ufw
                Status: active
                ###FAIL2BAN###
                ACTIVE
                ###SSHD###
                permitrootlogin no
                passwordauthentication no
                port 22
                ###SERVICES###
                fail2ban.service
                docker.service
                """,
                "ssh:203.0.113.10",
                "203.0.113.10",
                null
        );
        var plan = InstallPlanBuilder.build(snap, Set.of("security", "docker", "traefik", "mariadb"));
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("traefik") && a.status() == InstallPlan.Status.READY);
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("mariadb") && a.status() == InstallPlan.Status.READY);
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("security") && a.status() == InstallPlan.Status.SKIP);
    }

    @Test
    void deployReadyWhenSecuritySkipAndDockerPresent() throws Exception {
        var snap = new DiscoveryParser().parse(
                """
                ###DOCKER###
                PRESENT
                Docker version 29.0.0
                ###DEPLOY###
                ABSENT
                ###UFW###
                ENGINE=ufw
                Status: active
                ###FAIL2BAN###
                ACTIVE
                ###SSHD###
                permitrootlogin no
                passwordauthentication no
                port 22
                ###SERVICES###
                fail2ban.service
                docker.service
                """,
                "ssh:203.0.113.10",
                "203.0.113.10",
                null
        );
        var plan = InstallPlanBuilder.build(snap, Set.of("security", "docker", "deploy"));
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("deploy") && a.status() == InstallPlan.Status.READY);
    }

    @Test
    void deployStaysPlannedUntilSecuritySkip() throws Exception {
        var snap = new DiscoveryParser().parse(
                """
                ###DOCKER###
                PRESENT
                Docker version 29.0.0
                ###DEPLOY###
                ABSENT
                ###UFW###
                ENGINE=ufw
                Status: active
                ###FAIL2BAN###
                ACTIVE
                ###SSHD###
                permitrootlogin no
                passwordauthentication yes
                port 22
                """,
                "ssh:203.0.113.10",
                "203.0.113.10",
                null
        );
        var plan = InstallPlanBuilder.build(snap, Set.of("docker", "deploy"));
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("deploy") && a.status() == InstallPlan.Status.PLANNED);
    }

    @Test
    void dockerSkipsWhenEnginePresentAndNoRemainingDetails() throws Exception {
        var snap = new DiscoveryParser().parse(
                """
                ###DOCKER###
                PRESENT
                Docker version 29.0.0
                """,
                "ssh:203.0.113.10",
                "203.0.113.10",
                null
        );
        var plan = InstallPlanBuilder.build(snap, Set.of("docker"));
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("docker") && a.status() == InstallPlan.Status.SKIP);
    }

    @Test
    void dockerReadyWhenEnginePresentButDetailsRemain() throws Exception {
        var snap = new DiscoveryParser().parse(
                """
                ###DOCKER###
                PRESENT
                Docker version 29.0.0
                """,
                "ssh:57.128.186.38",
                "57.128.186.38",
                null
        );
        var plan = InstallPlanBuilder.build(
                snap,
                Set.of("docker"),
                java.util.Map.of("docker", java.util.List.of("compose", "docker-group"))
        );
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("docker") && a.status() == InstallPlan.Status.READY);
        assertThat(plan.actions()).anyMatch(a -> a.id().equals("docker") && a.reason().contains("détails"));
    }
}
