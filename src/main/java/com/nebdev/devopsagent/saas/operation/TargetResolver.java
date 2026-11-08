package com.nebdev.devopsagent.saas.operation;

import com.nebdev.devopsagent.saas.shared.SaasProperties;
import com.nebdev.devopsagent.saas.shared.TargetPolicy;
import org.springframework.stereotype.Component;

/**
 * Cible SSH résolue et contrôlée juste avant la connexion (anti-SSRF) ; la connexion vise l'adresse IP obtenue,
 * pas le nom, pour qu'une seconde résolution DNS ne puisse pas rediriger vers le réseau interne.
 */
@Component
public class TargetResolver {

    private final SaasProperties.Servers policy;

    public TargetResolver(SaasProperties properties) {
        this.policy = properties.getServers();
    }

    /** @throws TargetPolicy.Refused cible interne, interdite ou introuvable */
    public EnginePort.Target pinned(EnginePort.Target target) {
        String ip = TargetPolicy.resolveForConnection(target.host(), policy.isAllowPrivateTargets(), policy.denied())
                .getHostAddress();
        return new EnginePort.Target(ip, target.port(), target.user(), target.name());
    }
}
