package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.discovery.model.LiveSnapshot;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.ssh.SshConnector;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.state.StateStore;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class AppDeployService {

    private final AppIntentStore appIntentStore;
    private final StateStore stateStore;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final DiscoveryService discoveryService;

    public AppDeployService(
            AppIntentStore appIntentStore,
            StateStore stateStore,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            DiscoveryService discoveryService
    ) {
        this.appIntentStore = appIntentStore;
        this.stateStore = stateStore;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.discoveryService = discoveryService;
    }

    public DiscoveryService.DiscoveryResult deploy(String appId, SshTarget target, boolean confirmed)
            throws IOException {
        actionGuard.assertConfirmedChange("deploy-app", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        ServerSnapshot snap = lastSnapshot();
        if (snap == null || !LiveSnapshot.isLive(snap)) {
            throw new IllegalStateException("Analyse SSH réelle requise avant de déployer une app.");
        }
        if (!InstallPlanBuilder.securityPresent(snap)) {
            throw new IllegalStateException("Baseline sécurité incomplet. Pas d’app publique tant que Sécurité n’est pas SKIP.");
        }
        if (snap.docker() == null || !snap.docker().present()) {
            throw new IllegalStateException("Docker est requis. Installe Docker d’abord.");
        }
        if (snap.traefik() == null || !snap.traefik().present()) {
            throw new IllegalStateException("Traefik est requis. Installe Traefik d’abord.");
        }
        if (snap.deploy() == null || !snap.deploy().present()) {
            throw new IllegalStateException("Compte deploy requis. Installe le compte deploy d’abord.");
        }
        AppSpec app = appIntentStore.require(appId);
        String script = RoleScript.loadApp(app);
        String raw = sshConnector.runStdinScript(script, target, 600);
        if (raw.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(
                    "sudo sans mot de passe requis pour " + target.user() + ".");
        }
        if (raw.contains("NEED_DEPLOY_USER")) {
            throw new IllegalStateException("Compte deploy absent. Installe-le depuis la pile.");
        }
        if (raw.contains("NEED_TRAEFIK") || raw.contains("DOCKER_MISSING")) {
            throw new IllegalStateException("Docker et Traefik (réseau proxy) sont requis.");
        }
        if (raw.contains("NEED_DOCKERFILE")) {
            throw new IllegalStateException("Le dépôt n’a pas de Dockerfile à la racine. L’agent ne l’invente pas.");
        }
        if (raw.contains("GIT_CLONE_FAILED")) {
            throw new IllegalStateException("Clone Git impossible. Dépôt HTTPS public, branche existante.");
        }
        if (raw.contains("NEED_APP_SPEC")) {
            throw new IllegalStateException("Spécification d’app incomplète.");
        }
        if (raw.contains("UNSUPPORTED_OS")) {
            throw new IllegalStateException("Famille Debian/Ubuntu uniquement pour l’instant.");
        }
        if (!raw.contains("APP_DEPLOYED")) {
            throw new IllegalStateException("Le déploiement ne s’est pas terminé (APP_DEPLOYED absent).");
        }
        return discoveryService.discoverRemote(target);
    }

    private ServerSnapshot lastSnapshot() throws IOException {
        StateStore.InfrastructureState state = stateStore.loadLatest();
        return state == null ? null : state.lastDiscovery();
    }
}
