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
    private final AppDeployPlanService planService;
    private final StateStore stateStore;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final DiscoveryService discoveryService;

    public AppDeployService(
            AppIntentStore appIntentStore,
            AppDeployPlanService planService,
            StateStore stateStore,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            DiscoveryService discoveryService
    ) {
        this.appIntentStore = appIntentStore;
        this.planService = planService;
        this.stateStore = stateStore;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.discoveryService = discoveryService;
    }

    public AppDeployPlan plan(String appId) throws IOException {
        return planService.plan(appId);
    }

    public DiscoveryService.DiscoveryResult deploy(String appId, SshTarget target, boolean confirmed)
            throws IOException {
        return deploy(appId, target, confirmed, true);
    }

    public DiscoveryService.DiscoveryResult deploy(
            String appId,
            SshTarget target,
            boolean confirmed,
            boolean allowScaffold
    ) throws IOException {
        actionGuard.assertConfirmedChange("deploy-app", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        ServerSnapshot snap = lastSnapshot();
        if (snap == null || !LiveSnapshot.isLive(snap)) {
            throw new IllegalStateException(OperatorMessage.of(
                    "pas d’analyse SSH réelle en mémoire (fixture ou aucune discovery).",
                    "Étape Déployer — prérequis",
                    "clique « Analyser le VPS » avec IP + user SSH, puis réessaie Déployer."));
        }
        if (!InstallPlanBuilder.securityPresent(snap)) {
            throw new IllegalStateException(OperatorMessage.of(
                    "baseline sécurité incomplet sur ce VPS (UFW, fail2ban, SSH durci).",
                    "Étape Déployer — prérequis Sécurité",
                    "dans Installations, installe Sécurité jusqu’à SKIP, puis Déployer."));
        }
        if (snap.docker() == null || !snap.docker().present()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "Docker absent sur ce VPS.",
                    "Étape Déployer — prérequis Docker",
                    "installe Docker depuis Installations, réanalyse si besoin, puis Déployer."));
        }
        if (snap.traefik() == null || !snap.traefik().present()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "Traefik absent : sans lui l’app n’a pas d’entrée HTTP publique.",
                    "Étape Déployer — prérequis Traefik",
                    "installe Traefik (après Docker + sécurité), puis Déployer."));
        }
        if (snap.deploy() == null || !snap.deploy().present()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "compte système « deploy » absent.",
                    "Étape Déployer — prérequis Compte deploy",
                    "installe le compte deploy dans Installations (pas Analyser), puis Enregistrer / Déployer."));
        }

        AppSpec app = appIntentStore.require(appId);
        AppDeployPlan plan = planService.plan(appId);
        if (!plan.canProceed()) {
            throw new IllegalStateException(OperatorMessage.of(
                    plan.blocker() == null ? "déploiement impossible." : plan.blocker(),
                    "Popup confirmation — plan bloqué",
                    "corrige le dépôt ou attends un template pour cette stack."));
        }

        String scaffoldB64 = null;
        AppSpec effective = app;
        if (allowScaffold && plan.scaffoldOpt().isPresent()) {
            AppDeployPlan.Scaffold sc = plan.scaffold();
            scaffoldB64 = sc.dockerfileBase64();
            if (sc.port() != app.port()) {
                effective = new AppSpec(app.id(), app.gitUrl(), app.gitRef(), app.hostname(), sc.port());
            }
        } else {
            // Pas de rattrapage autorisé : garder l’ancien préflight strict.
            DockerfilePreflight.assertPresentOrSkip(app);
        }

        String script = RoleScript.loadApp(effective, scaffoldB64);
        String raw;
        try {
            raw = sshConnector.runStdinScript(script, target, 600);
        } catch (IOException e) {
            throw AppRemoteFailure.unknown(e, effective);
        }
        AppRemoteFailure.throwIfKnown(raw, effective);
        if (!raw.contains("APP_DEPLOYED")) {
            String excerpt = raw.replace('\n', ' ').strip();
            if (excerpt.length() > 180) {
                excerpt = excerpt.substring(0, 180);
            }
            throw new IllegalStateException(OperatorMessage.of(
                    "le script distant s’est arrêté sans marqueur APP_DEPLOYED (build ou compose en échec).",
                    "Étape Déployer — " + effective.id(),
                    "vérifie Dockerfile et le build. L’état déjà installé du VPS n’est pas effacé. Extrait : " + excerpt));
        }
        DiscoveryService.DiscoveryResult discovery = discoveryService.discoverRemote(target);
        PublicReachability.checkHttps(effective.hostname());
        return discovery;
    }

    private ServerSnapshot lastSnapshot() throws IOException {
        StateStore.InfrastructureState state = stateStore.loadLatest();
        return state == null ? null : state.lastDiscovery();
    }
}
