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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class InstallService {

    private static final Map<String, String> ACTION_BY_COMPONENT = Map.of(
            "security", "install-security",
            "docker", "install-docker",
            "traefik", "install-traefik",
            "mariadb", "install-mariadb",
            "deploy", "install-deploy",
            "redis", "install-redis",
            "backups", "install-backups"
    );

    private final StackIntentStore stackIntentStore;
    private final StateStore stateStore;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final DiscoveryService discoveryService;
    private final ServerProfiles serverProfiles;
    private final BackupService backupService;

    public InstallService(
            StackIntentStore stackIntentStore,
            StateStore stateStore,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            DiscoveryService discoveryService,
            ServerProfiles serverProfiles,
            BackupService backupService
    ) {
        this.serverProfiles = serverProfiles;
        this.stackIntentStore = stackIntentStore;
        this.stateStore = stateStore;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.discoveryService = discoveryService;
        this.backupService = backupService;
    }

    public InstallPlan plan() throws IOException {
        return plan(null);
    }

    /** @param host serveur ouvert dans le cockpit ; vide = dernière analyse, tous serveurs confondus */
    public InstallPlan plan(String host) throws IOException {
        ServerSnapshot snap = snapshotFor(host);
        if (!LiveSnapshot.isLive(snap)) {
            return new InstallPlan("", "", java.util.List.of());
        }
        StackIntentStore.Intent intent = stackIntentStore.loadIntent();
        Map<String, List<String>> details = StackPresence.subtract(intent.details(), StackPresence.of(snap));
        return InstallPlanBuilder.build(snap, new LinkedHashSet<>(intent.enabled()), details,
                serverProfiles.of(snap.host()));
    }

    public ApplyResult apply(String componentId, SshTarget target, boolean confirmed)
            throws IOException {
        actionGuard.assertConfirmedChange(actionName(componentId), confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        ServerSnapshot snap = snapshotFor(target.host());
        if (snap == null) {
            throw new IllegalStateException("Analyse d’abord le VPS. Sans snapshot, pas d’install.");
        }
        if (!LiveSnapshot.isLive(snap)) {
            throw new IllegalStateException("Analyse SSH réelle requise. Aucune fixture n’est utilisée.");
        }
        StackIntentStore.Intent intent = stackIntentStore.loadIntent();
        return applyResolved(componentId, target, snap, new LinkedHashSet<>(intent.enabled()), intent.details(),
                serverProfiles.of(target.host()));
    }

    /**
     * Pile voulue de l'instance ({@code stack-intent.json}) : utilisée par le SaaS, qui ne laisse pas les
     * organisations modifier ce fichier global (ADR-047).
     */
    public StackIntentStore.Intent instanceIntent() throws IOException {
        return stackIntentStore.loadIntent();
    }

    public ServerProfile profileOf(String host) throws IOException {
        return serverProfiles.of(host);
    }

    /**
     * Plan d'un composant sur un snapshot donné, avec pile et détails explicites (sans lire l'état local).
     * Utilisé par le SaaS pour un plan déterministe, puis recalculé à l'identique juste avant l'exécution.
     */
    public static InstallPlan.InstallAction planComponent(String componentId, ServerSnapshot snap, Set<String> wanted,
                                                         Map<String, List<String>> intentDetails, ServerProfile profile) {
        Map<String, List<String>> details = StackPresence.subtract(intentDetails, StackPresence.of(snap));
        Set<String> enabled = new LinkedHashSet<>(wanted);
        enabled.add(componentId);
        InstallPlan plan = InstallPlanBuilder.build(snap, enabled, details, profile);
        return plan.actions().stream().filter(a -> a.id().equals(componentId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Composant inconnu: " + componentId));
    }

    /** Script exact qui sera envoyé (pour hacher le plan : un script modifié invalide l'approbation). */
    public static String scriptFor(String componentId, ServerSnapshot snap, Map<String, List<String>> intentDetails,
                                   ServerProfile profile) {
        Map<String, List<String>> details = StackPresence.subtract(intentDetails, StackPresence.of(snap));
        List<String> selected = profile.roleDetails(componentId, details.getOrDefault(componentId, List.of()));
        return RoleScript.load(componentId, selected);
    }

    /** Cœur de {@link #apply} avec un snapshot frais et une pile explicite (SaaS). CHANGE déjà approuvé en amont. */
    public ApplyResult applyResolved(String componentId, SshTarget target, ServerSnapshot snap, Set<String> wanted,
                                     Map<String, List<String>> intentDetails, ServerProfile profile) throws IOException {
        actionGuard.assertConfirmedChange(actionName(componentId), true);
        forbiddenTargetGuard.assertAllowed(target.host());
        Map<String, List<String>> details = StackPresence.subtract(intentDetails, StackPresence.of(snap));
        // Pas d'ajout implicite : la console locale refuse un composant non coché (comportement d'origine).
        InstallPlan plan = InstallPlanBuilder.build(snap, new LinkedHashSet<>(wanted), details, profile);
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
        List<String> selected = profile.roleDetails(componentId, details.getOrDefault(componentId, List.of()));
        int timeoutSec = "backups".equals(componentId) ? 900
                : ("traefik".equals(componentId) || "mariadb".equals(componentId) || "redis".equals(componentId))
                ? 300 : 180;
        String script = RoleScript.load(componentId, selected);
        if ("backups".equals(componentId)) {
            script = "BACKUP_PASSPHRASE=" + backupService.passphrase() + "\n" + script;
        }
        String raw;
        try {
            raw = sshConnector.runStdinScript(script, target, timeoutSec);
        } catch (IOException e) {
            if ("backups".equals(componentId) && e.getMessage() != null) {
                BackupService.throwIfKnown(e.getMessage());
            }
            throw e;
        }
        assertRemoteMarkers(raw, componentId);

        Map<String, Object> extras = new LinkedHashMap<>();
        if ("backups".equals(componentId)) {
            handleBackupOffsite(raw, selected, target, extras);
        }

        DiscoveryService.DiscoveryResult discovery = discoveryService.discoverRemote(target);
        return new ApplyResult(discovery, extras);
    }

    private void handleBackupOffsite(
            String raw,
            List<String> selected,
            SshTarget target,
            Map<String, Object> extras
    ) throws IOException {
        boolean wantOffsite = selected.contains("offsite") || selected.isEmpty();
        boolean wantProof = selected.contains("restore-proof") || selected.isEmpty();
        if (!raw.contains("BACKUP_READY=") || (!wantOffsite && !wantProof)) {
            return;
        }
        BackupService.LocalBackup copy = backupService.copyFromRaw(raw, target);
        extras.put("backupFile", copy.file());
        extras.put("backupSha256", copy.sha256());
        if (wantProof && copy.check() != null) {
            extras.put("restoreProofOk", copy.check().ok());
            if (copy.check().ok()) {
                extras.put("RESTORE_PROOF_OK", true);
            } else {
                extras.put("restoreProofProblem", copy.check().problem());
            }
        }
    }

    private static void assertRemoteMarkers(String raw, String componentId) {
        if (raw.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(
                    "sudo sans mot de passe requis. Configure NOPASSWD ou connecte-toi en root.");
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
        if (raw.contains("NEED_INTERNAL")) {
            throw new IllegalStateException("Coche Redis interne avant d’installer.");
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
        if (raw.contains("REDIS_NOT_READY")) {
            throw new IllegalStateException("Le conteneur Redis n’a pas répondu à temps. Relancer l’install.");
        }
        if (raw.contains("NEED_BACKUP_PASSPHRASE")) {
            throw new IllegalStateException("Passphrase backup manquante (state/backup.key).");
        }
        if (raw.contains("OPENSSL_MISSING") || raw.contains("GZIP_MISSING")) {
            throw new IllegalStateException("openssl et gzip sont requis sur le VPS pour les backups.");
        }
        if (raw.contains("MARIADB_ROOT_PW_MISSING")) {
            throw new IllegalStateException("MariaDB tourne mais root.pw est absent. Réinstalle MariaDB.");
        }
        if (raw.contains("BACKUP_DUMP_FAILED") || raw.contains("BACKUP_DUMP_INCOMPLETE")) {
            BackupService.throwIfKnown(raw);
        }
        if ("backups".equals(componentId) && !raw.contains("BACKUPS_APPLIED")) {
            throw new IllegalStateException("Rôle backups : marqueur BACKUPS_APPLIED absent.");
        }
    }

    private ServerSnapshot snapshotFor(String host) throws IOException {
        StateStore.InfrastructureState state = host == null || host.isBlank()
                ? stateStore.loadLatest()
                : stateStore.load(host);
        return state == null ? null : state.lastDiscovery();
    }

    private static String actionName(String componentId) {
        String action = ACTION_BY_COMPONENT.get(componentId);
        if (action == null) {
            throw new IllegalArgumentException("Install non whitelistée: " + componentId);
        }
        return action;
    }

    public record ApplyResult(DiscoveryService.DiscoveryResult discovery, Map<String, Object> extras) {
        public ApplyResult(DiscoveryService.DiscoveryResult discovery) {
            this(discovery, Map.of());
        }
    }
}
