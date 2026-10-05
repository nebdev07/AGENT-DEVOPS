package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.config.DevopsProperties;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern BACKUP_READY = Pattern.compile("(?m)^BACKUP_READY=(.+)$");
    private static final Pattern BACKUP_SHA256 = Pattern.compile("(?m)^BACKUP_SHA256=([a-fA-F0-9]+)$");

    private final StackIntentStore stackIntentStore;
    private final StateStore stateStore;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final DiscoveryService discoveryService;
    private final DevopsProperties properties;
    private final ServerProfiles serverProfiles;

    public InstallService(
            StackIntentStore stackIntentStore,
            StateStore stateStore,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            DiscoveryService discoveryService,
            DevopsProperties properties,
            ServerProfiles serverProfiles
    ) {
        this.serverProfiles = serverProfiles;
        this.stackIntentStore = stackIntentStore;
        this.stateStore = stateStore;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.discoveryService = discoveryService;
        this.properties = properties;
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
        Map<String, List<String>> details = StackPresence.subtract(intent.details(), StackPresence.of(snap));
        ServerProfile profile = serverProfiles.of(target.host());
        InstallPlan plan = InstallPlanBuilder.build(snap, new LinkedHashSet<>(intent.enabled()), details, profile);
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
        int timeoutSec = ("traefik".equals(componentId) || "mariadb".equals(componentId)
                || "redis".equals(componentId) || "backups".equals(componentId))
                ? 300 : 180;
        String script = RoleScript.load(componentId, selected);
        String passphrase = null;
        if ("backups".equals(componentId)) {
            passphrase = loadOrCreateBackupPassphrase();
            script = "BACKUP_PASSPHRASE=" + passphrase + "\n" + script;
        }
        String raw = sshConnector.runStdinScript(script, target, timeoutSec);
        assertRemoteMarkers(raw, componentId);

        Map<String, Object> extras = new LinkedHashMap<>();
        if ("backups".equals(componentId)) {
            handleBackupOffsite(raw, selected, target, passphrase, extras);
        }

        DiscoveryService.DiscoveryResult discovery = discoveryService.discoverRemote(target);
        return new ApplyResult(discovery, extras);
    }

    private void handleBackupOffsite(
            String raw,
            List<String> selected,
            SshTarget target,
            String passphrase,
            Map<String, Object> extras
    ) throws IOException {
        Matcher ready = BACKUP_READY.matcher(raw);
        if (!ready.find()) {
            return;
        }
        String remotePath = ready.group(1).trim();
        if (!remotePath.startsWith("/opt/vps-autopilot/backups/") || !remotePath.endsWith(".enc")) {
            throw new IllegalStateException("Chemin BACKUP_READY invalide: " + remotePath);
        }
        Matcher shaM = BACKUP_SHA256.matcher(raw);
        String remoteSha = shaM.find() ? shaM.group(1).toLowerCase(Locale.ROOT) : "";

        boolean wantOffsite = selected.contains("offsite") || selected.isEmpty();
        boolean wantProof = selected.contains("restore-proof") || selected.isEmpty();
        if (!wantOffsite && !wantProof) {
            return;
        }

        String fileName = Path.of(remotePath).getFileName().toString();
        Path backupsDir = Path.of(properties.getStateDir()).resolve("backups");
        Files.createDirectories(backupsDir);
        Path localEnc = backupsDir.resolve(fileName);

        String b64Script = "set -eu\n"
                + "if [ \"$(id -u)\" -eq 0 ]; then SUDO=\"\"; else SUDO=\"sudo -n\"; $SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }; fi\n"
                + "$SUDO base64 -w0 " + shellSingleQuote(remotePath) + "\n";
        String b64Out = sshConnector.runStdinScript(b64Script, target, 180).trim();
        // Strip warning banners if any
        int lastNl = b64Out.lastIndexOf('\n');
        String b64 = lastNl >= 0 ? b64Out.substring(lastNl + 1).trim() : b64Out;
        if (b64.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(
                    "sudo sans mot de passe requis pour télécharger le backup.");
        }
        byte[] encBytes = Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
        Files.write(localEnc, encBytes);
        trySetOwnerOnly(localEnc);

        String localSha = sha256Hex(encBytes);
        if (!remoteSha.isBlank() && !remoteSha.equals(localSha)) {
            throw new IllegalStateException("SHA256 backup local ≠ distant.");
        }

        Path meta = backupsDir.resolve(".last-backup");
        Files.writeString(meta, ""
                + "file=" + fileName + "\n"
                + "remote=" + remotePath + "\n"
                + "sha256=" + localSha + "\n"
                + "copiedAt=" + java.time.Instant.now() + "\n", StandardCharsets.UTF_8);
        trySetOwnerOnly(meta);

        extras.put("backupLocalPath", localEnc.toAbsolutePath().normalize().toString());
        extras.put("backupSha256", localSha);

        if (wantProof && passphrase != null) {
            boolean ok = verifyRestoreProof(localEnc, passphrase);
            extras.put("restoreProofOk", ok);
            if (ok) {
                extras.put("RESTORE_PROOF_OK", true);
            }
        }
    }

    static boolean verifyRestoreProof(Path encFile, String passphrase) {
        try {
            byte[] file = Files.readAllBytes(encFile);
            if (file.length < 16) {
                return false;
            }
            String magic = new String(file, 0, 8, StandardCharsets.US_ASCII);
            if (!"Salted__".equals(magic)) {
                return false;
            }
            byte[] salt = Arrays.copyOfRange(file, 8, 16);
            byte[] cipherText = Arrays.copyOfRange(file, 16, file.length);
            // Compatible openssl enc -aes-256-cbc -pbkdf2 (iter=10000, key+iv).
            javax.crypto.SecretKeyFactory skf =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec =
                    new javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 10_000, (32 + 16) * 8);
            byte[] keyIv = skf.generateSecret(spec).getEncoded();
            javax.crypto.spec.SecretKeySpec key =
                    new javax.crypto.spec.SecretKeySpec(Arrays.copyOfRange(keyIv, 0, 32), "AES");
            javax.crypto.spec.IvParameterSpec iv =
                    new javax.crypto.spec.IvParameterSpec(Arrays.copyOfRange(keyIv, 32, 48));
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, iv);
            byte[] decrypted = cipher.doFinal(cipherText);
            if (decrypted.length < 2) {
                return false;
            }
            if (decrypted[0] == (byte) 0x1f && decrypted[1] == (byte) 0x8b) {
                return true;
            }
            String head = new String(decrypted, 0, Math.min(64, decrypted.length), StandardCharsets.UTF_8);
            return head.contains("--") || head.contains("MySQL") || head.contains("MariaDB") || head.contains("CREATE");
        } catch (Exception e) {
            return false;
        }
    }

    private String loadOrCreateBackupPassphrase() throws IOException {
        Path keyFile = Path.of(properties.getStateDir()).resolve("backup.key");
        Files.createDirectories(keyFile.getParent());
        if (Files.isRegularFile(keyFile)) {
            String existing = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
            if (existing.matches("[a-fA-F0-9]{32}")) {
                return existing.toLowerCase(Locale.ROOT);
            }
            throw new IllegalStateException("state/backup.key invalide (32 hex attendus).");
        }
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        Files.writeString(keyFile, hex, StandardCharsets.UTF_8);
        trySetOwnerOnly(keyFile);
        return hex;
    }

    private static void trySetOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX : best effort
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
