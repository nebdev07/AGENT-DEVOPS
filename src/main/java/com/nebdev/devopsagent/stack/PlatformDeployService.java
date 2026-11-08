package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.discovery.model.LiveSnapshot;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.ssh.LongJobTracker;
import com.nebdev.devopsagent.ssh.RemoteJobRunner;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.state.StateStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class PlatformDeployService {

    /** Premier build d’une plateforme type site + back-office Next + API Maven : ~20 min sur 4 vCPU sans cache. */
    static final int DEPLOY_TIMEOUT_SECONDS = 2700;

    private final PlatformIntentStore store;
    private final PlatformDeployPlanService planService;
    private final StateStore stateStore;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final RemoteJobRunner jobRunner;
    private final LongJobTracker longJobs;
    private final DiscoveryService discoveryService;
    private final GitDeployKeyStore gitDeployKeyStore;
    private final ServerProfiles serverProfiles;

    public PlatformDeployService(
            PlatformIntentStore store,
            PlatformDeployPlanService planService,
            StateStore stateStore,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            RemoteJobRunner jobRunner,
            LongJobTracker longJobs,
            DiscoveryService discoveryService,
            GitDeployKeyStore gitDeployKeyStore,
            ServerProfiles serverProfiles
    ) {
        this.serverProfiles = serverProfiles;
        this.store = store;
        this.planService = planService;
        this.stateStore = stateStore;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.jobRunner = jobRunner;
        this.longJobs = longJobs;
        this.discoveryService = discoveryService;
        this.gitDeployKeyStore = gitDeployKeyStore;
    }

    public AppDeployPlan plan(String platformId) throws IOException {
        return planService.plan(platformId);
    }

    public AppDeployPlan plan(String platformId, String serviceId) throws IOException {
        return planService.plan(platformId, serviceId);
    }

    /**
     * Résultat d’un déploiement : découverte post-déploiement + journal brut du rôle
     * (pour le rapport d’activité : {@code CLONED=…}, services réellement poussés).
     */
    public record DeployOutcome(DiscoveryService.DiscoveryResult discovery, String rawLog) {}

    public DeployOutcome deploy(String platformId, SshTarget target, boolean confirmed)
            throws IOException {
        return deploy(platformId, target, confirmed, null);
    }

    /**
     * @param serviceId optionnel — redeploy d’un seul service (V7-07)
     */
    public DeployOutcome deploy(String platformId, SshTarget target, boolean confirmed, String serviceId)
            throws IOException {
        actionGuard.assertConfirmedChange("deploy-platform", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        ServerSnapshot snap = snapshotFor(target.host());
        ServerProfile profile = serverProfiles.of(target.host());
        PlatformSpec platform = store.require(platformId);
        Map<String, String> gitKeys = new LinkedHashMap<>();
        PlatformServiceSpec only = PlatformDeployPlanService.resolveOnly(platform, serviceId);
        List<PlatformServiceSpec> targets = only == null ? platform.services() : List.of(only);
        for (PlatformServiceSpec s : targets) {
            if (GitUrls.ssh(s.gitUrl())) {
                gitKeys.put(GitDeployKeyStore.keyId(s.gitUrl()), gitDeployKeyStore.privateKeyBase64(s.gitUrl()));
            }
        }
        return deployResolved(platform, snap, target, profile, serviceId, gitKeys, longJobs::reportProgress);
    }

    /**
     * Cœur d'exécution réutilisable (console locale et moteur SaaS). Les gardes confirm/cible
     * restent dans {@link #deploy} ; le SaaS s'appuie sur Operation/Approval.
     */
    public DeployOutcome deployResolved(PlatformSpec platform, ServerSnapshot snap, SshTarget target,
                                        ServerProfile profile, String serviceId, Map<String, String> gitKeys,
                                        java.util.function.Consumer<String> progress) throws IOException {
        if (snap == null || !LiveSnapshot.isLive(snap)) {
            throw new IllegalStateException(OperatorMessage.of(
                    "pas d’analyse SSH réelle en mémoire.",
                    "Étape Déployer plateforme — prérequis",
                    "Analyser le VPS avec IP + user, puis Déployer."));
        }
        if (!InstallPlanBuilder.securityPresent(snap, List.of(), profile)) {
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
        PlatformServiceSpec only = PlatformDeployPlanService.resolveOnly(platform, serviceId);
        List<PlatformServiceSpec> targets = only == null ? platform.services() : List.of(only);
        boolean needRedis = only != null
                ? only.needs().contains("redis")
                : PlatformCompose.needsRedis(platform);
        boolean needDb = only != null
                ? only.needs().contains("mariadb")
                : PlatformCompose.needsDb(platform);
        if (needRedis && (snap.redis() == null || !snap.redis().present())) {
            throw new IllegalStateException(OperatorMessage.of(
                    "cette plateforme exige Redis, absent sur le VPS.",
                    "Déployer plateforme — Redis",
                    "installe Redis (Installations), puis Déployer."));
        }
        if (needDb && (snap.mariadb() == null || !snap.mariadb().present())) {
            throw new IllegalStateException(OperatorMessage.of(
                    "cette plateforme exige MariaDB, absente sur le VPS.",
                    "Déployer plateforme — MariaDB",
                    "installe MariaDB, puis Déployer."));
        }
        AppDeployPlan plan = planService.plan(platform, serviceId);
        if (!plan.canProceed()) {
            throw new IllegalStateException(OperatorMessage.of(
                    plan.blocker() == null ? "plan bloqué." : plan.blocker(),
                    "Popup confirmation — plateforme",
                    "corrige les Dockerfile Git, Enregistrer, puis Déployer."));
        }
        Map<String, String> keys = gitKeys == null ? Map.of() : gitKeys;
        String script = RoleScript.loadPlatform(platform, keys, profile, only == null ? null : only.id());
        String onlyId = only == null ? null : only.id();
        String raw;
        try {
            raw = jobRunner.run(script, target, DEPLOY_TIMEOUT_SECONDS, progress == null ? s -> {} : progress);
        } catch (IOException e) {
            throw AppRemoteFailure.unknownPlatform(e, platform, onlyId);
        }
        AppRemoteFailure.throwIfKnownPlatform(raw, platform, onlyId);
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
        if (raw.contains("NEED_SERVICE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "service ciblé absent du manifeste distant.",
                    "Déployer plateforme — service",
                    "vérifie l’id du service dans la fiche plateforme, Enregistrer, puis Déployer."));
        }
        if (!raw.contains("PLATFORM_DEPLOYED")) {
            String excerpt = raw.replace('\n', ' ').strip();
            if (excerpt.length() > 180) {
                excerpt = excerpt.substring(0, 180);
            }
            throw new IllegalStateException(OperatorMessage.of(
                    "le script s’est arrêté sans PLATFORM_DEPLOYED.",
                    "Déployer plateforme — " + platform.id()
                            + (only != null ? " / " + only.id() : ""),
                    "vérifie clones et builds. Extrait : " + excerpt));
        }
        DiscoveryService.DiscoveryResult discovery = discoveryService.discoverRemote(target);
        for (PlatformServiceSpec s : targets) {
            PublicReachability.checkHttpsUrl(PlatformCompose.publicUrl(platform, s, profile));
        }
        return new DeployOutcome(discovery, raw);
    }

    public record ServiceStatus(String service, String url, int httpCode, boolean online) {}

    /** READ : interroge l’URL publique de chaque service (HTTPS à certificat valide, sauf serveur « HTTP seul »), en parallèle. */
    public List<ServiceStatus> status(String platformId) throws IOException {
        PlatformSpec platform = store.require(platformId);
        List<CompletableFuture<ServiceStatus>> futures = new ArrayList<>();
        for (PlatformServiceSpec s : platform.services()) {
            String url = PlatformCompose.publicUrl(platform, s, serverProfiles.of(s.hostname()));
            futures.add(CompletableFuture.supplyAsync(() -> {
                int code = PublicReachability.probe(url);
                return new ServiceStatus(s.id(), url, code, code >= 200 && code < 500);
            }));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private ServerSnapshot snapshotFor(String host) throws IOException {
        StateStore.InfrastructureState state = stateStore.load(host);
        return state == null ? null : state.lastDiscovery();
    }
}
