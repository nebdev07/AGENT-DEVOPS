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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class InstallService {

    private static final Map<String, String> ACTION_BY_COMPONENT = Map.of(
            "security", "install-security",
            "docker", "install-docker",
            "traefik", "install-traefik",
            "mariadb", "install-mariadb",
            "deploy", "install-deploy"
    );

    private final StackIntentStore stackIntentStore;
    private final StateStore stateStore;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final DiscoveryService discoveryService;

    public InstallService(
            StackIntentStore stackIntentStore,
            StateStore stateStore,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            DiscoveryService discoveryService
    ) {
        this.stackIntentStore = stackIntentStore;
        this.stateStore = stateStore;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.discoveryService = discoveryService;
    }

    public InstallPlan plan() throws IOException {
        ServerSnapshot snap = lastSnapshot();
        if (!LiveSnapshot.isLive(snap)) {
            return new InstallPlan("", "", java.util.List.of());
        }
        StackIntentStore.Intent intent = stackIntentStore.loadIntent();
        Map<String, List<String>> details = StackPresence.subtract(intent.details(), StackPresence.of(snap));
        return InstallPlanBuilder.build(snap, new LinkedHashSet<>(intent.enabled()), details);
    }

    public DiscoveryService.DiscoveryResult apply(String componentId, SshTarget target, boolean confirmed)
            throws IOException {
        actionGuard.assertConfirmedChange(actionName(componentId), confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        ServerSnapshot snap = lastSnapshot();
        if (snap == null) {
            throw new IllegalStateException("Analyse d’abord le VPS. Sans snapshot, pas d’install.");
        }
        if (!LiveSnapshot.isLive(snap)) {
            throw new IllegalStateException("Analyse SSH réelle requise. Aucune fixture n’est utilisée.");
        }
        StackIntentStore.Intent intent = stackIntentStore.loadIntent();
        Map<String, List<String>> details = StackPresence.subtract(intent.details(), StackPresence.of(snap));
        InstallPlan plan = InstallPlanBuilder.build(snap, new LinkedHashSet<>(intent.enabled()), details);
        InstallPlan.InstallAction action = plan.actions().stream()
                .filter(a -> a.id().equals(componentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Composant non sélectionné dans la pile voulue: " + componentId));
        if (action.status() == InstallPlan.Status.REFUSED) {
            throw new IllegalStateException(action.reason());
        }
        if (action.status() == InstallPlan.Status.SKIP) {
            throw new IllegalStateException(action.reason());
        }
        if (action.status() != InstallPlan.Status.READY) {
            throw new IllegalStateException(action.reason());
        }
        List<String> selected = details.getOrDefault(componentId, List.of());
        int timeoutSec = ("traefik".equals(componentId) || "mariadb".equals(componentId)) ? 300 : 180;
        String raw = sshConnector.runStdinScript(RoleScript.load(componentId, selected), target, timeoutSec);
        if (raw.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(
                    "sudo sans mot de passe requis pour " + target.user() + ". Configure NOPASSWD ou connecte-toi en root.");
        }
        if (raw.contains("UNSUPPORTED_OS")) {
            throw new IllegalStateException("Rôle " + componentId + " : famille Debian/Ubuntu uniquement pour l’instant.");
        }
        if (raw.contains("NEED_AUTHORIZED_KEY")) {
            throw new IllegalStateException(
                    "Aucune clé dans authorized_keys. Impossible de couper le mot de passe SSH (risque de lockout).");
        }
        if (raw.contains("SSHD_TEST_FAILED") || raw.contains("SSHD_MISSING")) {
            throw new IllegalStateException("sshd -t a échoué. Le drop-in SSH n’a pas été activé.");
        }
        if (raw.contains("DOCKER_MISSING") || raw.contains("COMPOSE_MISSING")) {
            throw new IllegalStateException("Docker (ou Compose) est requis. Installe Docker d’abord.");
        }
        if (raw.contains("NEED_INSTANCE")) {
            throw new IllegalStateException("Coche l’instance MariaDB (conteneur) avant les grants par projet.");
        }
        if (raw.contains("NEED_SSH_DROPIN")) {
            throw new IllegalStateException(
                    "Drop-in SSH introuvable. Installe d’abord la sécurité (clés seulement).");
        }
        if (raw.contains("NEED_DEPLOY_USER")) {
            throw new IllegalStateException("Coche l’utilisateur deploy avant les clés / SSH.");
        }
        if (raw.contains("MARIADB_NOT_READY")) {
            throw new IllegalStateException("Le conteneur MariaDB n’a pas répondu à temps. Relancer l’install.");
        }
        return discoveryService.discoverRemote(target);
    }

    private ServerSnapshot lastSnapshot() throws IOException {
        StateStore.InfrastructureState state = stateStore.loadLatest();
        return state == null ? null : state.lastDiscovery();
    }

    private static String actionName(String componentId) {
        String action = ACTION_BY_COMPONENT.get(componentId);
        if (action == null) {
            throw new IllegalArgumentException("Install non whitelistée: " + componentId);
        }
        return action;
    }
}
