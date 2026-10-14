package com.nebdev.devopsagent.stack;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class RoleScript {

    private static final Set<String> ALLOWED = Set.of(
            "security", "docker", "traefik", "mariadb", "deploy", "redis", "backups", "app", "platform",
            "platform-restore", "platform-sql", "backup-drill", "platform-secret");
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9._-]{0,80}$");
    private static final Map<String, String> FLAGS = new LinkedHashMap<>();

    static {
        FLAGS.put("ufw", "APPLY_UFW");
        FLAGS.put("fail2ban", "APPLY_FAIL2BAN");
        FLAGS.put("ssh-harden", "APPLY_SSH");
        FLAGS.put("unattended", "APPLY_UPDATES");
        FLAGS.put("engine", "APPLY_DOCKER");
        FLAGS.put("compose", "APPLY_COMPOSE");
        FLAGS.put("docker-group", "APPLY_DOCKER_GROUP");
        FLAGS.put("proxy", "APPLY_PROXY");
        FLAGS.put("https", "APPLY_HTTPS");
        FLAGS.put("instance", "APPLY_INSTANCE");
        FLAGS.put("per-project", "APPLY_PER_PROJECT");
        FLAGS.put("user", "APPLY_USER");
        FLAGS.put("keys", "APPLY_KEYS");
        FLAGS.put("ssh-allow", "APPLY_SSH_ALLOW");
        FLAGS.put("internal", "APPLY_INTERNAL");
        FLAGS.put("encrypted-dump", "APPLY_ENCRYPTED_DUMP");
        FLAGS.put("offsite", "APPLY_OFFSITE");
        FLAGS.put("restore-proof", "APPLY_RESTORE_PROOF");
    }

    private RoleScript() {}

    public static String load(String id) {
        return load(id, List.of());
    }

    public static String load(String id, List<String> selected) {
        if (!ALLOWED.contains(id)) {
            throw new IllegalArgumentException("Rôle non exécutable: " + id);
        }
        String resource = "roles/" + id + "/apply.sh";
        String body;
        try {
            byte[] bytes = new ClassPathResource(resource).getInputStream().readAllBytes();
            body = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger " + resource, e);
        }
        if (selected == null || selected.isEmpty()) {
            if ("traefik".equals(id)) {
                // HTTPS actif par défaut (ADR-033) ; seul le profil serveur « HTTP seul » l’enlève (ADR-038).
                return "APPLY_PROXY=1\nAPPLY_HTTPS=1\n" + body;
            }
            return body;
        }
        Set<String> on = new LinkedHashSet<>(selected);
        if ("traefik".equals(id)) {
            on.add("proxy");
            if (!on.contains(ServerProfile.TRAEFIK_HTTP_ONLY)) {
                on.add("https");
            }
        }
        StringBuilder prefix = new StringBuilder();
        for (Map.Entry<String, String> e : FLAGS.entrySet()) {
            prefix.append(e.getValue()).append('=').append(on.contains(e.getKey()) ? "1" : "0").append('\n');
        }
        return prefix + body;
    }

    public static String loadApp(AppSpec app) {
        return loadApp(app, null);
    }

    public static String loadApp(AppSpec app, String scaffoldDockerfileBase64) {
        return loadApp(app, scaffoldDockerfileBase64, null);
    }

    public static String loadApp(AppSpec app, String scaffoldDockerfileBase64, String gitDeployKeyBase64) {
        if (app == null) {
            throw new IllegalArgumentException("App manquante.");
        }
        StringBuilder prefix = new StringBuilder();
        prefix.append("APP_SLUG=").append(app.id()).append('\n');
        prefix.append("APP_GIT=").append(app.gitUrl()).append('\n');
        prefix.append("APP_REF=").append(app.gitRef()).append('\n');
        prefix.append("APP_HOST=").append(app.hostname()).append('\n');
        prefix.append("APP_PORT=").append(app.port()).append('\n');
        appendGitKey(prefix, gitDeployKeyBase64);
        if (scaffoldDockerfileBase64 != null && !scaffoldDockerfileBase64.isBlank()) {
            String b64 = scaffoldDockerfileBase64.replaceAll("\\s", "");
            prefix.append("APP_SCAFFOLD_B64=").append(b64).append('\n');
        }
        return prefix + load("app");
    }

    public static String loadPlatform(PlatformSpec platform) {
        return loadPlatform(platform, Map.of());
    }

    /**
     * @param gitKeysById clé privée (base64) par {@link GitDeployKeyStore#keyId(String)} des dépôts SSH
     */
    public static String loadPlatform(PlatformSpec declared, Map<String, String> gitKeysById) {
        return loadPlatform(declared, gitKeysById, ServerProfile.DEFAULT);
    }

    public static String loadPlatform(PlatformSpec declared, Map<String, String> gitKeysById, ServerProfile profile) {
        if (declared == null) {
            throw new IllegalArgumentException("Plateforme manquante.");
        }
        PlatformSpec platform = declared.resolved();
        StringBuilder prefix = new StringBuilder();
        prefix.append("PLATFORM_ID=").append(platform.id()).append('\n');
        prefix.append("PLATFORM_NEED_DB=").append(PlatformCompose.needsDb(platform) ? "1" : "0").append('\n');
        prefix.append("PLATFORM_NEED_REDIS=").append(PlatformCompose.needsRedis(platform) ? "1" : "0").append('\n');
        prefix.append("PLATFORM_COMPOSE_B64=").append(b64(PlatformCompose.yaml(platform, profile))).append('\n');
        prefix.append("PLATFORM_MANIFEST_B64=").append(b64(PlatformCompose.manifest(platform))).append('\n');
        prefix.append("PLATFORM_FILES_B64=").append(b64(PlatformFiles.bundle(platform))).append('\n');
        if (gitKeysById != null && !gitKeysById.isEmpty()) {
            StringBuilder keys = new StringBuilder();
            gitKeysById.forEach((id, key) -> keys.append(id).append('\t').append(key.replaceAll("\\s", "")).append('\n'));
            prefix.append("GIT_KEYS_B64=").append(b64(keys.toString())).append('\n');
        }
        return prefix + load("platform");
    }

    /**
     * Restauration : les fichiers sont déjà dans {@code remoteDir} (SFTP). Noms de fichiers fixés par l’agent.
     */
    public static String loadPlatformRestore(
            PlatformSpec platform,
            String remoteDir,
            String sqlFile,
            String uploadsFile,
            String uploadsSubdir
    ) {
        return loadPlatformRestore(platform, remoteDir, sqlFile, uploadsFile, uploadsSubdir, true);
    }

    public static String loadPlatformRestore(
            PlatformSpec platform,
            String remoteDir,
            String sqlFile,
            String uploadsFile,
            String uploadsSubdir,
            boolean foreignKeyChecks
    ) {
        if (platform == null) {
            throw new IllegalArgumentException("Plateforme manquante.");
        }
        if (remoteDir == null || !remoteDir.matches("^/tmp/autopilot-restore-[a-z0-9-]{1,80}$")) {
            throw new IllegalArgumentException("Dossier distant de restauration invalide.");
        }
        for (String token : new String[] {sqlFile, uploadsFile, uploadsSubdir}) {
            if (token != null && (!SAFE_TOKEN.matcher(token).matches() || token.contains(".."))) {
                throw new IllegalArgumentException("Nom de fichier de restauration invalide.");
            }
        }
        List<String> services = new ArrayList<>();
        for (PlatformServiceSpec s : platform.services()) {
            services.add(s.id());
        }
        StringBuilder prefix = new StringBuilder();
        prefix.append("PLATFORM_ID=").append(platform.id()).append('\n');
        prefix.append("RESTORE_DIR=").append(remoteDir).append('\n');
        prefix.append("RESTORE_SQL=").append(sqlFile == null ? "" : sqlFile).append('\n');
        prefix.append("RESTORE_UPLOADS=").append(uploadsFile == null ? "" : uploadsFile).append('\n');
        prefix.append("UPLOADS_VOLUME=").append(PlatformCompose.uploadsVolume(platform)).append('\n');
        prefix.append("UPLOADS_SUBDIR=").append(uploadsSubdir == null ? "" : uploadsSubdir).append('\n');
        prefix.append("PLATFORM_SERVICES='").append(String.join(" ", services)).append("'\n");
        prefix.append("RESTORE_FK_CHECKS=").append(foreignKeyChecks ? "1" : "0").append('\n');
        return prefix + load("platform-restore");
    }

    public static String loadPlatformSql(PlatformSpec platform, String remoteDir, SqlConsolePolicy.Kind kind) {
        if (platform == null) {
            throw new IllegalArgumentException("Plateforme manquante.");
        }
        if (remoteDir == null || !remoteDir.matches("^/tmp/autopilot-sql-[a-z0-9-]{1,80}$")) {
            throw new IllegalArgumentException("Dossier distant de la console SQL invalide.");
        }
        String mode = switch (kind) {
            case READ -> "read";
            case WRITE -> "write";
        };
        return "PLATFORM_ID=" + platform.id() + "\nSQL_DIR=" + remoteDir + "\nSQL_MODE=" + mode + "\n" + load("platform-sql");
    }

    /**
     * Exercice de restauration : fichiers déjà envoyés dans {@code remoteDir}.
     * @param expectedTables nom de fichier → (base, tables attendues)
     */
    public static String loadBackupDrill(String remoteDir, Map<String, Map.Entry<String, Integer>> expectedTables) {
        return loadBackupDrill(remoteDir, expectedTables, null, Map.of(), 0);
    }

    /**
     * @param filesArchive   nom de l’archive des fichiers envoyée dans {@code remoteDir}, ou {@code null}
     * @param expectedFiles  fichiers attendus par volume
     * @param extractedBytes taille cumulée des fichiers (contrôle de place dans /var/tmp)
     */
    public static String loadBackupDrill(
            String remoteDir,
            Map<String, Map.Entry<String, Integer>> expectedTables,
            String filesArchive,
            Map<String, Integer> expectedFiles,
            long extractedBytes
    ) {
        if (remoteDir == null || !remoteDir.matches("^/tmp/autopilot-restore-drill-[a-z0-9-]{1,80}$")) {
            throw new IllegalArgumentException("Dossier distant de l’exercice de restauration invalide.");
        }
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, Map.Entry<String, Integer>> e : expectedTables.entrySet()) {
            String file = e.getKey();
            String db = e.getValue().getKey();
            if (!SAFE_TOKEN.matcher(file).matches() || file.contains("..") || !db.matches("^[A-Za-z0-9_$-]{1,64}$")) {
                throw new IllegalArgumentException("Nom de fichier ou de base invalide pour l’exercice : " + file + " / " + db);
            }
            manifest.append(file).append('\t').append(db).append('\t').append(e.getValue().getValue()).append('\n');
        }
        StringBuilder prefix = new StringBuilder();
        prefix.append("DRILL_DIR=").append(remoteDir).append('\n');
        prefix.append("DRILL_MANIFEST_B64=").append(manifest.isEmpty() ? "" : b64(manifest.toString())).append('\n');
        if (filesArchive != null) {
            if (!SAFE_TOKEN.matcher(filesArchive).matches() || filesArchive.isEmpty() || filesArchive.contains("..")
                    || extractedBytes < 0) {
                throw new IllegalArgumentException("Archive de fichiers invalide pour l’exercice : " + filesArchive);
            }
            StringBuilder files = new StringBuilder();
            for (Map.Entry<String, Integer> e : expectedFiles.entrySet()) {
                if (!e.getKey().matches("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$") || e.getKey().contains("..")) {
                    throw new IllegalArgumentException("Nom de volume invalide pour l’exercice : " + e.getKey());
                }
                files.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            prefix.append("DRILL_FILES=").append(filesArchive).append('\n');
            prefix.append("DRILL_FILES_MANIFEST_B64=").append(b64(files.toString())).append('\n');
            prefix.append("DRILL_FILES_BYTES=").append(extractedBytes).append('\n');
        }
        return prefix + load("backup-drill");
    }

    /**
     * Secrets d’exécution d’une plateforme. La valeur part en base64 dans le script envoyé sur l’entrée standard
     * (jamais en argument de commande) ; {@code value} est ignorée sauf pour {@code SET}.
     */
    public static String loadPlatformSecret(String platformId, PlatformSecrets.Mode mode, String serviceId,
                                            String name, String value, boolean restart) {
        PlatformSecrets.requireId(platformId);
        StringBuilder prefix = new StringBuilder();
        prefix.append("PLATFORM_ID=").append(platformId).append('\n');
        prefix.append("SECRET_MODE=").append(mode.script()).append('\n');
        boolean targetsOne = switch (mode) {
            case LIST -> false;
            case SET, UNSET -> true;
        };
        if (targetsOne) {
            PlatformSecrets.requireId(serviceId);
            PlatformSecrets.requireName(name);
            prefix.append("SECRET_SERVICE=").append(serviceId).append('\n');
            prefix.append("SECRET_NAME=").append(name).append('\n');
            prefix.append("SECRET_RESTART=").append(restart ? "1" : "0").append('\n');
        }
        if (mode == PlatformSecrets.Mode.SET) {
            PlatformSecrets.requireValue(value);
            prefix.append("SECRET_VALUE_B64=").append(b64(value)).append('\n');
        }
        return prefix + load("platform-secret");
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void appendGitKey(StringBuilder prefix, String gitDeployKeyBase64) {
        if (gitDeployKeyBase64 != null && !gitDeployKeyBase64.isBlank()) {
            prefix.append("GIT_DEPLOY_KEY_B64=").append(gitDeployKeyBase64.replaceAll("\\s", "")).append('\n');
        }
    }
}
