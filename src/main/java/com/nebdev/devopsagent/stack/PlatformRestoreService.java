package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.ssh.RemoteJobRunner;
import com.nebdev.devopsagent.ssh.SshConnector;
import com.nebdev.devopsagent.ssh.SshTarget;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Restauration des données d’une plateforme : dump SQL (MySQL/MariaDB) et archive de fichiers → VPS (SFTP + import).
 * CHANGE confirmé. La base existante est sauvegardée sur le VPS avant d’être remplacée.
 */
@Service
public class PlatformRestoreService {

    static final int RESTORE_TIMEOUT_SECONDS = 1800;
    private static final Pattern SUBDIR = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,47}$");
    private static final String WHERE = "Bande Plateformes — Restaurer les données";

    private final PlatformIntentStore store;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final RemoteJobRunner jobRunner;
    private final SecureRandom random = new SecureRandom();

    public PlatformRestoreService(
            PlatformIntentStore store,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            RemoteJobRunner jobRunner
    ) {
        this.store = store;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.jobRunner = jobRunner;
    }

    public record RestoreFile(Path path, String originalName) {}

    public record RestoreResult(String platformId, List<String> report) {}

    public RestoreResult restore(
            String platformId,
            SshTarget target,
            boolean confirmed,
            RestoreFile sql,
            RestoreFile uploads,
            String uploadsSubdir,
            boolean foreignKeyChecks
    ) throws IOException {
        actionGuard.assertConfirmedChange("restore-platform", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        PlatformSpec platform = store.require(platformId);
        if (sql == null && uploads == null) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "aucun fichier choisi.",
                    WHERE,
                    "choisis un dump SQL (.sql ou .sql.gz) et/ou une archive de fichiers (.tar.gz ou .zip)."));
        }
        if (sql != null && !PlatformCompose.needsDb(platform)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "la plateforme « " + platform.id() + " » n’a pas de base MariaDB.",
                    WHERE,
                    "coche MariaDB sur le service qui utilise la base, Enregistrer, puis Restaurer."));
        }
        if (uploads != null && !PlatformCompose.anyUploads(platform)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "la plateforme « " + platform.id() + " » n’a pas de volume fichiers.",
                    WHERE,
                    "coche Volume fichiers sur le service concerné, Enregistrer, puis Restaurer."));
        }
        String subdir = normalizeSubdir(uploadsSubdir);
        if (uploads != null && !subdir.isEmpty() && isAgentFilesBackup(uploads.originalName())) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "une sauvegarde de fichiers de l’agent se restaure à la racine du volume.",
                    WHERE,
                    "laisse le sous-dossier vide, puis Restaurer."));
        }
        Map<String, Path> files = new LinkedHashMap<>();
        String sqlName = null;
        String uploadsName = null;
        if (sql != null) {
            sqlName = sqlRemoteName(sql.originalName());
            files.put(sqlName, sql.path());
        }
        if (uploads != null) {
            uploadsName = archiveRemoteName(uploads.originalName());
            files.put(uploadsName, uploads.path());
        }
        String remoteDir = "/tmp/autopilot-restore-" + platform.id() + "-" + HexFormat.of().formatHex(nextBytes());
        String script = RoleScript.loadPlatformRestore(platform, remoteDir, sqlName, uploadsName, subdir, foreignKeyChecks);
        sshConnector.uploadFiles(target, remoteDir, files);
        String raw;
        try {
            raw = jobRunner.run(script, target, RESTORE_TIMEOUT_SECONDS);
        } catch (IOException e) {
            raw = e.getMessage() == null ? "" : e.getMessage();
            throwIfKnown(raw);
            throw new IllegalStateException(OperatorMessage.of(
                    "la restauration s’est arrêtée.",
                    WHERE,
                    "extrait : " + excerpt(raw)), e);
        }
        throwIfKnown(raw);
        if (!raw.contains("PLATFORM_RESTORED")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "le script s’est arrêté sans PLATFORM_RESTORED.",
                    WHERE,
                    "extrait : " + excerpt(raw)));
        }
        return new RestoreResult(platform.id(), reportLines(raw));
    }

    static String sqlRemoteName(String original) {
        String n = original == null ? "" : original.trim().toLowerCase(Locale.ROOT);
        if (n.endsWith(".sql.gz")) {
            return "dump.sql.gz";
        }
        if (n.endsWith(".sql")) {
            return "dump.sql";
        }
        throw new IllegalArgumentException(OperatorMessage.of(
                "le dump « " + original + " » n’est pas un .sql ni un .sql.gz.",
                WHERE,
                "exporte avec mysqldump / mariadb-dump (fichier .sql, ou compressé .sql.gz)."));
    }

    static boolean isAgentFilesBackup(String original) {
        return original != null && original.trim().toLowerCase(Locale.ROOT).endsWith(".tar.gz.enc");
    }

    static String archiveRemoteName(String original) {
        String n = original == null ? "" : original.trim().toLowerCase(Locale.ROOT);
        if (isAgentFilesBackup(n)) {
            return "uploads.tar.gz.enc";
        }
        if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) {
            return "uploads.tar.gz";
        }
        if (n.endsWith(".tar")) {
            return "uploads.tar";
        }
        if (n.endsWith(".zip")) {
            return "uploads.zip";
        }
        throw new IllegalArgumentException(OperatorMessage.of(
                "l’archive « " + original + " » n’est pas un .tar.gz, .tgz, .tar, .zip ni une sauvegarde de l’agent.",
                WHERE,
                "compresse le dossier des fichiers (contenu à la racine de l’archive)."));
    }

    static String normalizeSubdir(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return "";
        }
        if (s.contains("..") || !SUBDIR.matcher(s).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "sous-dossier « " + raw.trim() + " » invalide.",
                    WHERE,
                    "un seul nom de dossier dans le volume, ex. uploads (ou vide = racine /app/data)."));
        }
        return s;
    }

    static List<String> reportLines(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("DB_BACKUP=") || t.startsWith("SQL_RESTORED=") || t.startsWith("TABLES=")
                    || t.startsWith("UPLOADS_BACKUP=") || t.startsWith("UPLOADS_RESTORED=") || t.startsWith("FILES=")
                    || t.startsWith("SERVICES_STOPPED=") || t.startsWith("SERVICES_STARTED=")
                    || t.startsWith("FK_CHECKS=") || t.startsWith("DB_ROLLED_BACK=") || t.startsWith("UPLOADS_ROLLED_BACK=")) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    static void throwIfKnown(String raw) {
        if (raw.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’utilisateur SSH n’a pas sudo sans mot de passe.",
                    WHERE,
                    "utilise le compte de l’agent (sudoers NOPASSWD), puis Restaurer."));
        }
        if (raw.contains("NEED_MARIADB")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "conteneur mariadb absent.",
                    WHERE,
                    "installe MariaDB (Installations), puis Restaurer."));
        }
        if (raw.contains("DB_BACKUP_FAILED")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "la sauvegarde de la base actuelle a échoué : rien n’a été remplacé.",
                    WHERE,
                    "vérifie l’espace disque du VPS, puis Restaurer."));
        }
        if (raw.contains("SQL_IMPORT_FAILED")) {
            String state = raw.contains("DB_ROLLED_BACK=")
                    ? "la base a été remise dans son état d’avant (sauvegarde réimportée)"
                    : raw.contains("DB_ROLLBACK_FAILED")
                            ? "ATTENTION : le retour à la sauvegarde a aussi échoué, la base est incomplète (sauvegarde dans le dossier backups)"
                            : "il n’y avait pas de base avant, elle est incomplète";
            String todo = raw.contains("ERROR 1452") || raw.contains("ERROR 1215") || raw.contains("ERROR 1216")
                    ? "le dump crée des lignes avant celles qu’elles référencent (ou contient des lignes orphelines) : "
                            + "décoche « Vérifier les clés étrangères pendant l’import », puis Restaurer."
                    : "vérifie le dump (MySQL/MariaDB, une seule base).";
            throw new IllegalStateException(OperatorMessage.of(
                    "l’import SQL a échoué ; " + state + ". Les services ont été relancés.",
                    WHERE,
                    todo + " Extrait : " + importError(raw)));
        }
        if (raw.contains("BAD_ARCHIVE_PATH")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’archive contient des chemins absolus ou « .. ».",
                    WHERE,
                    "recrée l’archive depuis l’intérieur du dossier (tar -C dossier -czf uploads.tar.gz .)."));
        }
        if (raw.contains("UPLOADS_EXTRACT_FAILED") || raw.contains("BAD_ARCHIVE_TYPE")) {
            String state = raw.contains("UPLOADS_ROLLED_BACK=")
                    ? " Les fichiers d’avant ont été remis."
                    : raw.contains("UPLOADS_ROLLBACK_FAILED")
                            ? " ATTENTION : la remise des fichiers d’avant a aussi échoué (copie dans le dossier backups)."
                            : "";
            throw new IllegalStateException(OperatorMessage.of(
                    "l’archive de fichiers n’a pas pu être extraite." + state,
                    WHERE,
                    "vérifie qu’elle s’ouvre en local (.tar.gz ou .zip), puis Restaurer."));
        }
        if (raw.contains("NEED_BACKUP_PASSPHRASE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "la phrase de chiffrement des sauvegardes est absente du VPS : l’archive de l’agent ne peut pas être lue.",
                    WHERE,
                    "Installations › Sauvegardes chiffrées, puis Restaurer."));
        }
        if (raw.contains("DOCKER_MISSING")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "Docker absent ou inaccessible.",
                    WHERE,
                    "installe Docker, puis Restaurer."));
        }
        if (raw.contains("RESTORE_FILE_MISSING") || raw.contains("NEED_RESTORE_SPEC") || raw.contains("BAD_RESTORE_PATH")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "fichier envoyé introuvable sur le VPS.",
                    WHERE,
                    "relance Restaurer (l’envoi SFTP a pu être interrompu)."));
        }
    }

    private byte[] nextBytes() {
        byte[] b = new byte[4];
        random.nextBytes(b);
        return b;
    }

    static String importError(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("ERROR ")) {
                return t.length() > 300 ? t.substring(0, 300) + "…" : t;
            }
        }
        return excerpt(raw);
    }

    private static String excerpt(String raw) {
        String e = raw.replace('\n', ' ').strip();
        return e.length() > 240 ? e.substring(e.length() - 240) : e;
    }
}
