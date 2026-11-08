package com.nebdev.devopsagent.saas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.saas.engine.LocalEngineAdapter;
import com.nebdev.devopsagent.saas.operation.EnginePort;
import com.nebdev.devopsagent.saas.operation.OperationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adaptateur réel du moteur (pas le faux) sur l'analyse de référence d'un Ubuntu vierge : conversion du snapshot,
 * plan d'installation réel (InstallPlanBuilder), hash du script réel et résumé d'audit pour l'IA.
 */
class SaasRealEngineTest extends SaasTestSupport {

    @Autowired LocalEngineAdapter real;
    @Autowired DiscoveryService discovery;

    @Test
    void realPlansAreDeterministicAndBoundToTheRoleScript() throws Exception {
        var snap = discovery.discoverFixture("classpath:fixtures/ubuntu-vps2-blank.txt").snapshot();
        Map<String, Object> payload = mapper.convertValue(snap, new TypeReference<>() {});
        // Comme une vraie analyse SSH (le moteur refuse à juste titre de planifier sur une fixture).
        payload.put("source", "ssh:198.51.100.20");
        payload.put("host", "198.51.100.20");
        EnginePort.Target target = new EnginePort.Target("198.51.100.20", 22, "ubuntu");

        EnginePort.Plan security = real.plan(new EnginePort.PlanRequest(OperationType.INSTALL_SECURITY, target, Map.of(), payload, null));
        EnginePort.Plan again = real.plan(new EnginePort.PlanRequest(OperationType.INSTALL_SECURITY, target, Map.of(), payload, null));
        assertThat(security.scriptSha256()).hasSize(64).isEqualTo(again.scriptSha256());
        assertThat(security.steps()).isNotEmpty();
        assertThat(security.toMap()).isEqualTo(again.toMap());

        EnginePort.Plan discover = real.plan(new EnginePort.PlanRequest(OperationType.DISCOVER, target, Map.of(), null, null));
        assertThat(discover.effects()).containsExactly("Aucun changement sur le serveur");

        // Traefik avant la sécurité : prérequis manquant, refus explicite.
        assertThatThrownBy(() -> real.plan(new EnginePort.PlanRequest(OperationType.INSTALL_TRAEFIK, target, Map.of(), payload, null)))
                .isInstanceOf(EnginePort.PlanRefused.class);
        // Sauvegarde immédiate sans rôle installé : refus.
        assertThatThrownBy(() -> real.plan(new EnginePort.PlanRequest(OperationType.RUN_BACKUP, target, Map.of(), payload, null)))
                .isInstanceOf(EnginePort.PlanRefused.class);
        assertThatThrownBy(() -> real.plan(new EnginePort.PlanRequest(OperationType.INSTALL_DOCKER, target, Map.of(), null, null)))
                .isInstanceOf(EnginePort.PlanRefused.class).extracting("code").isEqualTo("discovery_required");

        EnginePort.Summary summary = real.summarize(payload);
        assertThat(summary.facts()).containsKeys("osFamily", "auditGrade", "auditScore");
        assertThat(summary.findings()).isNotEmpty();
        assertThat(summary.toString()).doesNotContain("198.51.100");
    }
}
