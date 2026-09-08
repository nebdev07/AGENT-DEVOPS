package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StackPolicyTest {

    @Test
    void defaultsKeepPolicyCore() {
        var eval = StackPolicy.evaluate(StackPolicy.defaults());
        assertThat(eval.enabled()).contains("security", "docker", "traefik", "mariadb", "deploy", "backups");
        assertThat(eval.enabled()).doesNotContain("redis", "nginx");
        assertThat(eval.policyBreak()).isFalse();
        assertThat(eval.warnings()).isEmpty();
    }

    @Test
    void redisOffIsSilent() {
        var eval = StackPolicy.evaluate(Set.of("security", "docker", "traefik", "mariadb", "deploy", "backups"));
        assertThat(eval.policyBreak()).isFalse();
        assertThat(eval.warnings()).isEmpty();
    }

    @Test
    void dockerOffDropsDependentsAndBreaksPolicy() {
        var eval = StackPolicy.evaluate(Set.of("security", "traefik", "mariadb", "redis", "backups"));
        assertThat(eval.enabled()).doesNotContain("traefik", "mariadb", "redis", "docker", "deploy");
        assertThat(eval.policyBreak()).isTrue();
        assertThat(eval.warnings().stream().map(StackPolicy.Warning::message).toList())
                .anyMatch(m -> m.contains("Docker"));
    }

    @Test
    void uncheckingSshHardenBreaksPolicy() {
        var details = new java.util.LinkedHashMap<String, java.util.List<String>>();
        details.put("security", java.util.List.of("ufw", "fail2ban", "unattended"));
        var eval = StackPolicy.evaluate(StackPolicy.defaults(), details);
        assertThat(eval.policyBreak()).isTrue();
        assertThat(eval.warnings().stream().map(StackPolicy.Warning::componentId).toList())
                .contains("security.ssh-harden");
    }

    @Test
    void catalogMarksFail2banPresentAndNotDefault() {
        var catalog = StackService.catalogWithPresence(java.util.Map.of("security", java.util.List.of("fail2ban")));
        var fail2ban = catalog.stream()
                .filter(c -> c.id().equals("security"))
                .flatMap(c -> c.details().stream())
                .filter(d -> d.id().equals("fail2ban"))
                .findFirst()
                .orElseThrow();
        assertThat(fail2ban.present()).isTrue();
        assertThat(fail2ban.defaultEnabled()).isFalse();
    }

    @Test
    void alreadyInstalledDetailIsNotAPolicyGap() {
        var present = java.util.Map.of("security", java.util.List.of("fail2ban"));
        var details = new java.util.LinkedHashMap<String, java.util.List<String>>();
        details.put("security", java.util.List.of("ufw", "ssh-harden", "unattended"));
        var eval = StackPolicy.evaluate(StackPolicy.defaults(), details, present);
        assertThat(eval.details().get("security")).doesNotContain("fail2ban");
        assertThat(eval.warnings().stream().map(StackPolicy.Warning::componentId).toList())
                .doesNotContain("security.fail2ban");
    }

    @Test
    void nginxIsNotAnInstallOption() {
        var eval = StackPolicy.evaluate(Set.of("security", "docker", "nginx"));
        assertThat(eval.enabled()).doesNotContain("nginx");
        assertThat(StackCatalog.all().stream().map(StackComponent::id)).doesNotContain("nginx");
        assertThat(StackCatalog.notOffered().stream().map(StackCatalog.RefusedOffer::id)).contains("nginx");
    }
}
