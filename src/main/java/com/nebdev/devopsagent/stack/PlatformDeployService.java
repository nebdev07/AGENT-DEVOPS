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
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PlatformDeployService {

    /** Premier build d’un produit type ARC (2 Next + Maven) : ~20 min sur 4 vCPU sans cache. */
    static final int DEPLOY_TIMEOUT_SECONDS = 2700;

    private final PlatformIntentStore store;
    private final PlatformDeployPlanService planService;
    private final StateStore stateStore;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final DiscoveryService discoveryService;
    private final GitDeployKeyStore gitDeployKeyStore;

    public PlatformDeployService(
            PlatformIntentStore store,
            PlatformDeployPlanService planService,
            StateStore stateStore,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            DiscoveryService discoveryService,
            GitDeployKeyStore gitDeployKeyStore
    ) {
        this.store = store;
        this.planService = planService;
        this.stateStore = stateStore;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.discoveryService = discoveryService;
        this.gitDeployKeyStore = gitDeployKeyStore;
    }

    public AppDeployPlan plan(String platformId) throws IOException {
        return planService.plan(platformId);
    }

    public DiscoveryService.DiscoveryResult deploy(String platformId, SshTarget target, boolean confirmed)
            throws IOException {
        actionGuard.assertConfirmedChange("deploy-platform", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        ServerSnapshot snap = lastSnapshot();
        if (snap == null || !LiveSnapshot.isLive(snap)) {
            throw new IllegalStateException(OperatorMessage.of(
                    "pas d’analyse SSH réelle en mémoire.",
                    "Étape Déployer plateforme — prérequis",
                    "Analyser le VPS avec IP + user, puis Déployer."));
        }
        if (!InstallPlanBuilder.securityPresent(snap)) {
            throw new IllegalStateException(OperatorMessage.of(
                    "baseline sécurité incomplet.",
                    "Déployer plateforme — Sécurité",
                    "installe Sécurité jusqu’à SKIP."));
        }
        if (snap.docker() == null || !snap.docker().present()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "Docker absent.",
                    "Déployer plateforme — Docker",
                    "installe Docker, puis Déployer."));
        }
        if (snap.traefik() == null || !snap.traefik().present()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "Traefik absent.",
                    "Déployer plateforme — Traefik",
                    "installe Traefik, puis Déployer."));
        }
        if (snap.deploy() == null || !snap.deploy().present()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "compte deploy absent.",
                    "Déployer plateforme — Compte deploy",
                    "installe le compte deploy, puis Déployer."));
        }
        PlatformSpec platform = store.require(platformId);
        if (PlatformCompose.needsRedis(platform) && (snap.redis() == null || !snap.redis().present())) {
            throw new IllegalStateException(OperatorMessage.of(
                    "cette plateforme exige Redis, absent sur le VPS.",
                    "Déployer plateforme — Redis",
                    "installe Redis (Installations), puis Déployer."));
        }
        if (PlatformCompose.needsDb(platform) && (snap.mariadb() == null || !snap.mariadb().present())) {
            throw new IllegalStateException(OperatorMessage.of(
                    "cette plateforme exige MariaDB, absente sur le VPS.",
                    "Déployer plateforme — MariaDB",
                    "installe MariaDB, puis Déployer."));
        }
        AppDeployPlan plan = planService.plan(platformId);
        if (!plan.canProceed()) {
            throw new IllegalStateException(OperatorMessage.of(
                    plan.blocker() == null ? "plan bloqué." : plan.blocker(),
                    "Popup confirmation — plateforme",
                    "corrige les Dockerfile Git, Enregistrer, puis Déployer."));
        }
        Map<String, String> gitKeys = new LinkedHashMap<>();
        for (PlatformServiceSpec s : platform.services()) {
            if (GitUrls.ssh(s.gitUrl())) {
                gitKeys.put(GitDeployKeyStore.keyId(s.gitUrl()), gitDeployKeyStore.privateKeyBase64(s.gitUrl()));
            }
        }
        String script = RoleScript.loadPlatform(platform, gitKeys);
        String raw;
        try {
            raw = sshConnector.runStdinScript(script, target, DEPLOY_TIMEOUT_SECONDS);
        } catch (IOException e) {
            AppSpec dummy = AppSpec.normalized(
                    platform.id(),
                    platform.services().get(0).gitUrl(),
                    platform.services().get(0).gitRef(),
                    platform.services().get(0).hostname(),
                    platform.services().get(0).port()
            );
            throw AppRemoteFailure.unknown(e, dummy);
        }
        AppSpec dummy = AppSpec.normalized(
                platform.id(),
                platform.services().get(0).gitUrl(),
                platform.services().get(0).gitRef(),
                platform.services().get(0).hostname(),
                platform.services().get(0).port()
        );
        AppRemoteFailure.throwIfKnown(raw, dummy);
        if (raw.contains("NEED_REDIS")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "Redis exigé mais conteneur redis absent au moment du script.",
                    "Déployer plateforme — Redis",
                    "installe Redis, réanalyse, Déployer."));
        }
        if (raw.contains("NEED_MARIADB")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "MariaDB exigée mais conteneur mariadb absent.",
                    "Déployer plateforme — MariaDB",
                    "installe MariaDB, puis Déployer."));
        }
        if (raw.contains("NEED_PLATFORM_SPEC")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "spécification plateforme incomplète côté agent.",
                    "Déployer plateforme",
                    "ré-enregistre la plateforme, puis Déployer."));
        }
        if (!raw.contains("PLATFORM_DEPLOYED")) {
            String excerpt = raw.replace('\n', ' ').strip();
            if (excerpt.length() > 180) {
                excerpt = excerpt.substring(0, 180);
            }
            throw new IllegalStateException(OperatorMessage.of(
                    "le script s’est arrêté sans PLATFORM_DEPLOYED.",
                    "Déployer plateforme — " + platform.id(),
                    "vérifie clones et builds. Extrait : " + excerpt));
        }
        DiscoveryService.DiscoveryResult discovery = discoveryService.discoverRemote(target);
        for (PlatformServiceSpec s : platform.services()) {
            PublicReachability.checkHttpsUrl(PlatformCompose.publicUrl(platform, s));
        }
        return discovery;
    }

    private ServerSnapshot lastSnapshot() throws IOException {
        StateStore.InfrastructureState state = stateStore.loadLatest();
        return state == null ? null : state.lastDiscovery();
    }
}
