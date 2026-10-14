package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.ssh.RemoteJobRunner;
import com.nebdev.devopsagent.ssh.SshConnector;
import com.nebdev.devopsagent.ssh.SshTarget;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sauvegardes de l’agent (ADR-034, ADR-041) : dump MariaDB + archive des volumes de fichiers (BIZ-11), sauvegarde
 * à la demande, copie sur le poste opérateur (SHA-256 contrôlé), vérification réelle du contenu, exercice de
 * restauration (base et dossier jetables), restauration d’une plateforme depuis une sauvegarde.
 */
@Service
public class BackupService {

    static final int BACKUP_TIMEOUT_SECONDS = 1800;
    /** Copies gardées sur ce poste par serveur (BIZ-12), en plus de la dernière restaurée avec succès. */
    static final int LOCAL_KEEP = 10;
    static final Pattern FILE_NAME = Pattern.compile("^dump-\\d{8}T\\d{6}Z\\.sql\\.gz\\.enc$");
    static final Pattern FILES_ARCHIVE_NAME = Pattern.compile("^files-\\d{8}T\\d{6}Z\\.tar\\.gz\\.enc$");
    private static final Pattern READY = Pattern.compile("(?m)^BACKUP_READY=(\\S+)\\s*$");
    private static final Pattern SHA = Pattern.compile("(?m)^BACKUP_SHA256=([a-fA-F0-9]{64})\\s*$");
    private static final Pattern FILES_READY = Pattern.compile("(?m)^FILES_READY=(\\S+)\\s*$");
    private static final Pattern FILES_SHA = Pattern.compile("(?m)^FILES_SHA256=([a-fA-F0-9]{64})\\s*$");
    private static final Pattern DRILL_VOL = Pattern.compile("(?m)^DRILL_VOL=([^:\\s]+):(\\d+):(\\d+)\\s*$");
    private static final Pattern COPY_READY = Pattern.compile("(?m)^COPY_READY=(/tmp/autopilot-backup-[a-f0-9]+\\.enc)\\s*$");
    private static final Pattern DRILL_DB = Pattern.compile("(?m)^DRILL_DB=([^:\\s]+):(\\d+):(\\d+)\\s*$");
    private static final String REMOTE_DIR = "/opt/vps-autopilot/backups/";
    private static final String DRILL_FILES = "files.tar.gz.enc";
    private static final String WHERE = "Bande Sauvegardes";
    private static final String SUDO_PREAMBLE = "set -eu\n"
            + "if [ \"$(id -u)\" -eq 0 ]; then SUDO=\"\"; else SUDO=\"sudo -n\"; "
            + "$SUDO true || { echo NEED_PASSWORDLESS_SUDO; exit 1; }; fi\n";

    private final DevopsProperties properties;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final RemoteJobRunner jobRunner;
    private final PlatformRestoreService platformRestoreService;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public BackupService(
            DevopsProperties properties,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            RemoteJobRunner jobRunner,
            PlatformRestoreService platformRestoreService
    ) {
        this.properties = properties;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.jobRunner = jobRunner;
        this.platformRestoreService = platformRestoreService;
    }

    /** Résultat de la vérification du contenu d’une copie locale. */
    public record Check(
            boolean ok,
            boolean noDatabase,
            Map<String, Integer> databases,
            int applicationTables,
            String problem,
            String checkedAt
    ) {}

    /** Résultat d’un exercice de restauration : une ligne par base (attendu / obtenu). */
    public record Drill(boolean ok, List<String> lines, String drilledAt) {}

    /** Vérification de l’archive des volumes de fichiers (BIZ-11). */
    public record FilesCheck(
            boolean ok,
            Map<String, Integer> volumes,
            int files,
            long bytes,
            String problem,
            String checkedAt
    ) {}

    /** Archive des volumes de fichiers prise en même temps que le dump ({@code files-<horodatage>.tar.gz.enc}). */
    public record FilesPart(String file, long sizeBytes, String sha256, FilesCheck check) {}

    /** Copie locale d’une sauvegarde, avec ses dernières preuves ; {@code files} absent si aucun volume. */
    public record LocalBackup(
            String host,
            String file,
            long sizeBytes,
            String sha256,
            String remotePath,
            String copiedAt,
            Check check,
            Drill drill,
            FilesPart files
    ) {}

    // ---------------------------------------------------------------- phrase de passe

    /** Phrase de chiffrement (32 hex), créée au premier usage dans {@code state/backup.key}. */
    public String passphrase() throws IOException {
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
        random.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        Files.writeString(keyFile, hex, StandardCharsets.UTF_8);
        ownerOnly(keyFile);
        return hex;
    }

    // ---------------------------------------------------------------- sauvegarde + copie

    /** Lance une sauvegarde maintenant sur le VPS, la copie sur ce poste et la vérifie. CHANGE confirmé. */
    public LocalBackup runNow(SshTarget target, boolean confirmed) throws IOException {
        actionGuard.assertConfirmedChange("run-backup", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        String script = SUDO_PREAMBLE
                + "$SUDO test -x /usr/local/bin/vps-autopilot-backup || { echo NEED_BACKUP_ROLE; exit 1; }\n"
                + "$SUDO /usr/local/bin/vps-autopilot-backup\n";
        String raw;
        try {
            raw = jobRunner.run(script, target, BACKUP_TIMEOUT_SECONDS);
        } catch (IOException e) {
            raw = e.getMessage() == null ? "" : e.getMessage();
            throwIfKnown(raw);
            throw new IllegalStateException(OperatorMessage.of(
                    "la sauvegarde s’est arrêtée.", WHERE, "extrait : " + excerpt(raw)), e);
        }
        throwIfKnown(raw);
        return copyFromRaw(raw, target);
    }

    /**
     * Après un script qui a imprimé {@code BACKUP_READY}/{@code BACKUP_SHA256} : copie + vérification.
     * @return {@code null} si le script n’a produit aucune sauvegarde
     */
    public LocalBackup copyFromRaw(String raw, SshTarget target) throws IOException {
        Matcher ready = READY.matcher(raw);
        if (!ready.find()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "le VPS n’a pas annoncé de fichier de sauvegarde (BACKUP_READY absent).",
                    WHERE, "extrait : " + excerpt(raw)));
        }
        Matcher sha = SHA.matcher(raw);
        String dumpPath = ready.group(1).trim();
        Path dump = copyToLocal(target, dumpPath, sha.find() ? sha.group(1).toLowerCase(Locale.ROOT) : "", FILE_NAME);
        Matcher files = FILES_READY.matcher(raw);
        if (files.find()) {
            String filesPath = files.group(1).trim();
            if (!Path.of(filesPath).getFileName().toString().equals(filesNameFor(dump.getFileName().toString()))) {
                throw new IllegalStateException("Archive de fichiers sans le même horodatage que le dump : " + filesPath);
            }
            Matcher fsha = FILES_SHA.matcher(raw);
            copyToLocal(target, filesPath, fsha.find() ? fsha.group(1).toLowerCase(Locale.ROOT) : "", FILES_ARCHIVE_NAME);
        }
        LocalBackup copied = verify(target.host(), dump.getFileName().toString());
        purgeLocal(target.host());
        return copied;
    }

    // ---------------------------------------------------------------- purge des copies locales (BIZ-12)

    /** Une copie locale vue par la purge : nom du dump et succès de son dernier exercice. */
    record PurgeCandidate(String file, boolean drillOk) {}

    /**
     * Copies à supprimer : on garde les {@code keep} plus récentes et, en plus, la plus récente dont l’exercice
     * de restauration a réussi (la dernière preuve de restauration n’est jamais effacée).
     */
    static List<String> purgePlan(List<PurgeCandidate> candidates, int keep) {
        List<PurgeCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparing(PurgeCandidate::file).reversed())
                .toList();
        String lastDrilled = sorted.stream().filter(PurgeCandidate::drillOk).map(PurgeCandidate::file)
                .findFirst().orElse(null);
        List<String> out = new ArrayList<>();
        for (int i = keep; i < sorted.size(); i++) {
            String f = sorted.get(i).file();
            if (!f.equals(lastDrilled)) {
                out.add(f);
            }
        }
        return out;
    }

    /** Supprime les copies locales anciennes du serveur (dump, archive des fichiers, fiches json). */
    public List<String> purgeLocal(String host) throws IOException {
        Path dir = hostDirIfExists(host);
        if (dir == null) {
            return List.of();
        }
        List<PurgeCandidate> candidates = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> FILE_NAME.matcher(f.getFileName().toString()).matches()).toList()) {
                Object drill = readMeta(p).get("drill");
                boolean ok = drill instanceof Map<?, ?> d && Boolean.TRUE.equals(d.get("ok"));
                candidates.add(new PurgeCandidate(p.getFileName().toString(), ok));
            }
        }
        List<String> removed = purgePlan(candidates, LOCAL_KEEP);
        for (String name : removed) {
            Path dump = dir.resolve(name);
            Path files = dir.resolve(filesNameFor(name));
            for (Path p : List.of(dump, metaPath(dump), files, metaPath(files))) {
                Files.deleteIfExists(p);
            }
        }
        return removed;
    }

    /** {@code dump-<horodatage>.sql.gz.enc} → {@code files-<horodatage>.tar.gz.enc}. */
    static String filesNameFor(String dumpName) {
        if (!FILE_NAME.matcher(dumpName).matches()) {
            throw new IllegalArgumentException("Nom de sauvegarde invalide.");
        }
        return "files-" + dumpName.substring("dump-".length(), dumpName.length() - ".sql.gz.enc".length()) + ".tar.gz.enc";
    }

    /**
     * Copie la sauvegarde la plus récente du VPS (celle du minuteur, par exemple). Pas de confirmation : rien n’est
     * modifié hors d’une copie temporaire dans /tmp ; refusé sur un serveur en lecture seule (données d’un tiers).
     */
    public LocalBackup fetchLatest(SshTarget target) throws IOException {
        forbiddenTargetGuard.assertAllowed(target.host());
        String script = SUDO_PREAMBLE
                + "F=\"$($SUDO sh -c 'ls -1t " + REMOTE_DIR + "dump-*.sql.gz.enc 2>/dev/null | head -n 1')\"\n"
                + "[ -n \"$F\" ] || { echo NO_BACKUP_FILE; exit 1; }\n"
                + "echo \"BACKUP_READY=$F\"\n"
                + "echo \"BACKUP_SHA256=$($SUDO sha256sum \"$F\" | awk '{print $1}')\"\n"
                + "S=\"${F##*/dump-}\"; G=\"" + REMOTE_DIR + "files-${S%.sql.gz.enc}.tar.gz.enc\"\n"
                + "if $SUDO test -f \"$G\"; then echo \"FILES_READY=$G\"; "
                + "echo \"FILES_SHA256=$($SUDO sha256sum \"$G\" | awk '{print $1}')\"; fi\n";
        String raw;
        try {
            raw = sshConnector.runStdinScript(script, target, 120);
        } catch (IOException e) {
            raw = e.getMessage() == null ? "" : e.getMessage();
            throwIfKnown(raw);
            throw e;
        }
        return copyFromRaw(raw, target);
    }

    LocalBackup copyOffsite(SshTarget target, String remotePath, String remoteSha) throws IOException {
        Path local = copyToLocal(target, remotePath, remoteSha, FILE_NAME);
        return verify(target.host(), local.getFileName().toString());
    }

    private Path copyToLocal(SshTarget target, String remotePath, String remoteSha, Pattern allowedName) throws IOException {
        String name = Path.of(remotePath).getFileName().toString();
        if (!remotePath.equals(REMOTE_DIR + name) || !allowedName.matcher(name).matches()) {
            throw new IllegalStateException("Chemin de sauvegarde inattendu : " + remotePath);
        }
        String tmp = "/tmp/autopilot-backup-" + HexFormat.of().formatHex(nextBytes(8)) + ".enc";
        String prepare = SUDO_PREAMBLE
                + "find /tmp -maxdepth 1 -name 'autopilot-backup-*.enc' -user \"$(id -un)\" -mmin +120 -delete "
                + "2>/dev/null || true\n"
                + "$SUDO install -m 600 -o \"$(id -un)\" '" + remotePath + "' '" + tmp + "'\n"
                + "echo COPY_READY=" + tmp + "\n";
        String raw = sshConnector.runStdinScript(prepare, target, 300);
        Matcher m = COPY_READY.matcher(raw);
        if (!m.find()) {
            throwIfKnown(raw);
            throw new IllegalStateException("Copie temporaire de la sauvegarde impossible sur le VPS : " + excerpt(raw));
        }
        Path dir = hostDir(target.host());
        Path local = dir.resolve(name);
        Path partial = dir.resolve(name + ".part");
        try {
            sshConnector.downloadFile(target, tmp, partial);
        } finally {
            try {
                sshConnector.runStdinScript("rm -f '" + tmp + "'\n", target, 60);
            } catch (IOException | RuntimeException ignored) {
                // la copie temporaire (600, propriétaire SSH) sera écrasée au prochain essai ; /tmp est nettoyé au boot
            }
        }
        String localSha = sha256(partial);
        if (!remoteSha.isBlank() && !remoteSha.equals(localSha)) {
            Files.deleteIfExists(partial);
            throw new IllegalStateException("Empreinte SHA-256 différente entre le VPS et la copie locale : copie rejetée.");
        }
        Files.move(partial, local, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ownerOnly(local);
        Map<String, Object> meta = readMeta(local);
        meta.put("host", target.host());
        meta.put("remotePath", remotePath);
        meta.put("sha256", localSha);
        meta.put("copiedAt", Instant.now().toString());
        meta.remove("check");
        meta.remove("drill");
        writeMeta(local, meta);
        return local;
    }

    // ---------------------------------------------------------------- consultation / vérification

    public List<LocalBackup> list(String host) throws IOException {
        Path dir = hostDirIfExists(host);
        if (dir == null) {
            return List.of();
        }
        List<LocalBackup> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> FILE_NAME.matcher(f.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .toList()) {
                out.add(describe(host, p));
            }
        }
        return out;
    }

    /** Déchiffre et lit toute la copie locale ; résultat enregistré à côté du fichier. */
    public LocalBackup verify(String host, String file) throws IOException {
        Path local = requireLocal(host, file);
        BackupArchive.Verification v = BackupArchive.verify(local, passphrase());
        Check check = new Check(v.ok(), v.noDatabase(), v.tablesByDatabase(), v.applicationTables(), v.problem(),
                Instant.now().toString());
        Map<String, Object> meta = readMeta(local);
        meta.put("check", check);
        writeMeta(local, meta);
        Path files = companion(local);
        if (files != null) {
            BackupArchive.FilesVerification fv = BackupArchive.verifyFiles(files, passphrase());
            Map<String, Object> fmeta = readMeta(files);
            fmeta.put("check", new FilesCheck(fv.ok(), fv.filesByVolume(), fv.totalFiles(), fv.totalBytes(),
                    fv.problem(), Instant.now().toString()));
            writeMeta(files, fmeta);
        }
        return describe(host, local);
    }

    /** Archive des fichiers copiée avec ce dump, ou {@code null}. */
    private static Path companion(Path dump) {
        Path files = dump.resolveSibling(filesNameFor(dump.getFileName().toString()));
        return Files.isRegularFile(files) ? files : null;
    }

    // ---------------------------------------------------------------- exercice de restauration

    /**
     * Importe chaque base applicative de la sauvegarde dans une base jetable du VPS, compte les tables puis la
     * supprime ; extrait l’archive des fichiers dans un dossier jetable et compte les fichiers par volume. Les bases
     * et volumes en service ne sont pas touchés. CHANGE confirmé.
     */
    public LocalBackup drill(SshTarget target, String file, boolean confirmed) throws IOException {
        actionGuard.assertConfirmedChange("restore-drill", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        Path local = requireLocal(target.host(), file);
        String pass = passphrase();
        BackupArchive.Verification v = BackupArchive.verify(local, pass);
        if (!v.ok()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "cette sauvegarde n’est pas exploitable : " + v.problem(),
                    WHERE, "lance « Sauvegarder maintenant », puis refais l’exercice sur la nouvelle copie."));
        }
        Path filesArchive = companion(local);
        BackupArchive.FilesVerification fv = filesArchive == null ? null : BackupArchive.verifyFiles(filesArchive, pass);
        if (fv != null && !fv.ok()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’archive des fichiers de cette sauvegarde n’est pas exploitable : " + fv.problem(),
                    WHERE, "lance « Sauvegarder maintenant », puis refais l’exercice sur la nouvelle copie."));
        }
        List<String> databases = v.noDatabase() ? List.of() : v.applicationDatabases();
        List<String> lines = new ArrayList<>();
        boolean ok;
        if (databases.isEmpty() && fv == null) {
            lines.add("ni base applicative ni volume de fichiers dans cette sauvegarde : rien à restaurer");
            ok = true;
        } else {
            Path work = Files.createTempDirectory("autopilot-drill-");
            try {
                Map<String, Path> uploads = new LinkedHashMap<>();
                Map<String, Map.Entry<String, Integer>> manifest = new LinkedHashMap<>();
                int i = 0;
                for (String db : databases) {
                    String name = "db-" + (++i) + ".sql.gz";
                    Path gz = work.resolve(name);
                    int tables = BackupArchive.extractDatabase(local, pass, db, gz);
                    uploads.put(name, gz);
                    manifest.put(name, Map.entry(db, tables));
                }
                if (fv != null) {
                    uploads.put(DRILL_FILES, filesArchive);
                }
                String remoteDir = "/tmp/autopilot-restore-drill-" + HexFormat.of().formatHex(nextBytes(4));
                String script = fv == null
                        ? RoleScript.loadBackupDrill(remoteDir, manifest)
                        : RoleScript.loadBackupDrill(remoteDir, manifest, DRILL_FILES, fv.filesByVolume(), fv.totalBytes());
                sshConnector.uploadFiles(target, remoteDir, uploads);
                String raw;
                try {
                    raw = jobRunner.run(script, target, BACKUP_TIMEOUT_SECONDS);
                } catch (IOException e) {
                    raw = e.getMessage() == null ? "" : e.getMessage();
                    throwIfKnown(raw);
                    if (!raw.contains("DRILL_IMPORT_FAILED") && !raw.contains("DRILL_FILES_")) {
                        throw new IllegalStateException(OperatorMessage.of(
                                "l’exercice de restauration s’est arrêté.", WHERE, "extrait : " + excerpt(raw)), e);
                    }
                }
                ok = raw.contains("DRILL_DONE");
                if (databases.isEmpty()) {
                    lines.add("aucune base applicative dans cette sauvegarde");
                }
                lines.addAll(drillLines(raw));
                ok &= drillCountsMatch(raw);
                if (lines.isEmpty()) {
                    ok = false;
                    lines.add("aucun résultat renvoyé par le VPS : " + excerpt(raw));
                }
            } finally {
                deleteTree(work);
            }
        }
        Map<String, Object> meta = readMeta(local);
        meta.put("drill", new Drill(ok, List.copyOf(lines), Instant.now().toString()));
        writeMeta(local, meta);
        return describe(target.host(), local);
    }

    static List<String> drillLines(String raw) {
        List<String> lines = new ArrayList<>();
        Matcher m = DRILL_DB.matcher(raw);
        while (m.find()) {
            boolean same = m.group(2).equals(m.group(3));
            lines.add(m.group(1) + " : " + m.group(3) + " table(s) restaurée(s) sur " + m.group(2)
                    + (same ? "" : " — ÉCART"));
        }
        Matcher vol = DRILL_VOL.matcher(raw);
        while (vol.find()) {
            boolean same = vol.group(2).equals(vol.group(3));
            lines.add("volume " + vol.group(1) + " : " + vol.group(3) + " fichier(s) restauré(s) sur " + vol.group(2)
                    + (same ? "" : " — ÉCART"));
        }
        for (String line : raw.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("DRILL_IMPORT_FAILED=")) {
                lines.add("échec d’import : " + t.substring("DRILL_IMPORT_FAILED=".length()));
            } else if (t.startsWith("DRILL_ERR: ")) {
                lines.add(t.substring("DRILL_ERR: ".length()));
            } else if (t.equals("DRILL_FILES_EXTRACT_FAILED")) {
                lines.add("fichiers : extraction de l’archive impossible sur le VPS");
            } else if (t.startsWith("DRILL_FILES_NO_SPACE=")) {
                String[] p = t.substring("DRILL_FILES_NO_SPACE=".length()).split(":");
                lines.add("fichiers : exercice non fait, place insuffisante dans /var/tmp (besoin "
                        + megabytes(p[0]) + " Mo, libre " + megabytes(p.length > 1 ? p[1] : "0") + " Mo)");
            }
        }
        return lines;
    }

    static boolean drillCountsMatch(String raw) {
        if (raw.contains("DRILL_IMPORT_FAILED") || raw.contains("DRILL_FILES_EXTRACT_FAILED")
                || raw.contains("DRILL_FILES_NO_SPACE")) {
            return false;
        }
        for (Pattern p : List.of(DRILL_DB, DRILL_VOL)) {
            Matcher m = p.matcher(raw);
            while (m.find()) {
                if (!m.group(2).equals(m.group(3))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long megabytes(String bytes) {
        try {
            return Long.parseLong(bytes.trim()) / (1024 * 1024);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- restauration d’une plateforme

    /**
     * Remplace la base {@code app_<id>} et le volume de fichiers de la plateforme par ceux de la sauvegarde (ce qui
     * s’y trouve). Le volume est remis à l’identique : les fichiers ajoutés depuis sont retirés (copie de l’existant
     * gardée sur le VPS). CHANGE confirmé.
     */
    public PlatformRestoreService.RestoreResult restorePlatform(
            String platformId,
            SshTarget target,
            String file,
            boolean confirmed,
            boolean foreignKeyChecks
    ) throws IOException {
        actionGuard.assertConfirmedChange("restore-platform", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        if (platformId == null || !platformId.matches("^[a-z0-9][a-z0-9-]{0,47}$")) {
            throw new IllegalArgumentException("Identifiant de plateforme invalide.");
        }
        Path local = requireLocal(target.host(), file);
        String pass = passphrase();
        String db = "app_" + platformId.replace('-', '_');
        boolean hasDb = BackupArchive.verify(local, pass).tablesByDatabase().containsKey(db);
        Path filesArchive = companion(local);
        boolean hasFiles = false;
        if (filesArchive != null) {
            BackupArchive.FilesVerification fv = BackupArchive.verifyFiles(filesArchive, pass);
            if (!fv.ok()) {
                throw new IllegalStateException(OperatorMessage.of(
                        "l’archive des fichiers de cette sauvegarde n’est pas exploitable : " + fv.problem(),
                        WHERE, "choisis une autre sauvegarde."));
            }
            hasFiles = fv.filesByVolume().containsKey(PlatformCompose.uploadsVolumeName(platformId));
        }
        if (!hasDb && !hasFiles) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "ni la base « " + db + " » ni les fichiers de « " + platformId + " » ne sont dans la sauvegarde "
                            + file + ".",
                    WHERE, "choisis une sauvegarde prise après le premier déploiement de la plateforme."));
        }
        Path gz = Files.createTempFile("autopilot-restore-", ".sql.gz");
        try {
            PlatformRestoreService.RestoreFile sql = null;
            if (hasDb) {
                BackupArchive.extractDatabase(local, pass, db, gz);
                sql = new PlatformRestoreService.RestoreFile(gz, "backup.sql.gz");
            }
            PlatformRestoreService.RestoreFile uploads = hasFiles
                    ? new PlatformRestoreService.RestoreFile(filesArchive, filesArchive.getFileName().toString())
                    : null;
            return platformRestoreService.restore(platformId, target, confirmed, sql, uploads, null, foreignKeyChecks);
        } finally {
            Files.deleteIfExists(gz);
        }
    }

    // ---------------------------------------------------------------- outils

    static void throwIfKnown(String raw) {
        if (raw.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’utilisateur SSH n’a pas sudo sans mot de passe.", WHERE,
                    "utilise le compte de l’agent (sudoers NOPASSWD)."));
        }
        if (raw.contains("NEED_BACKUP_ROLE") || raw.contains("NO_BACKUP_FILE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "les sauvegardes ne sont pas installées sur ce VPS.", WHERE,
                    "Installations › Sauvegardes chiffrées, puis réessaie."));
        }
        if (raw.contains("BACKUP_DUMP_FAILED")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’export MariaDB a échoué : aucune sauvegarde n’a été écrite.", WHERE,
                    "vérifie que le conteneur mariadb tourne. " + dumpError(raw)));
        }
        if (raw.contains("BACKUP_DUMP_INCOMPLETE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’export MariaDB est incomplet (marque de fin absente) : fichier rejeté.", WHERE,
                    "vérifie l’espace disque du VPS, puis relance."));
        }
        if (raw.contains("BACKUP_FILES_FAILED")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "la base est sauvegardée sur le VPS, mais l’archive des fichiers a échoué : rien n’a été copié.", WHERE,
                    "vérifie l’espace disque du VPS, puis relance. " + filesError(raw)));
        }
        if (raw.contains("NEED_BACKUP_PASSPHRASE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "la phrase de chiffrement des sauvegardes est absente du VPS.", WHERE,
                    "Installations › Sauvegardes chiffrées, puis réessaie."));
        }
        if (raw.contains("MARIADB_ROOT_PW_MISSING")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "MariaDB tourne mais root.pw est absent.", WHERE, "réinstalle MariaDB depuis Installations."));
        }
        if (raw.contains("NEED_MARIADB")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "conteneur mariadb absent : l’exercice a besoin d’un MariaDB pour importer.", WHERE,
                    "installe MariaDB (Installations), puis refais l’exercice."));
        }
        if (raw.contains("DOCKER_MISSING")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "Docker absent ou inaccessible.", WHERE, "installe Docker, puis réessaie."));
        }
        if (raw.contains("DRILL_FILE_MISSING") || raw.contains("NEED_DRILL_SPEC") || raw.contains("BAD_DRILL_FILE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "fichier envoyé introuvable sur le VPS.", WHERE, "relance l’exercice (envoi SFTP interrompu)."));
        }
    }

    private static String dumpError(String raw) {
        for (String line : raw.split("\\R")) {
            if (line.startsWith("DUMP_ERR: ")) {
                return "Erreur : " + line.substring("DUMP_ERR: ".length()).strip();
            }
        }
        return "";
    }

    private static String filesError(String raw) {
        for (String line : raw.split("\\R")) {
            if (line.startsWith("FILES_ERR: ")) {
                return "Erreur : " + line.substring("FILES_ERR: ".length()).strip();
            }
        }
        return "";
    }

    private LocalBackup describe(String host, Path local) throws IOException {
        Map<String, Object> meta = readMeta(local);
        Check check = meta.containsKey("check") ? json.convertValue(meta.get("check"), Check.class) : null;
        Drill drill = meta.containsKey("drill") ? json.convertValue(meta.get("drill"), Drill.class) : null;
        FilesPart filesPart = null;
        Path files = companion(local);
        if (files != null) {
            Map<String, Object> fmeta = readMeta(files);
            filesPart = new FilesPart(
                    files.getFileName().toString(),
                    Files.size(files),
                    (String) fmeta.getOrDefault("sha256", ""),
                    fmeta.containsKey("check") ? json.convertValue(fmeta.get("check"), FilesCheck.class) : null);
        }
        return new LocalBackup(
                host,
                local.getFileName().toString(),
                Files.size(local),
                (String) meta.getOrDefault("sha256", ""),
                (String) meta.getOrDefault("remotePath", ""),
                (String) meta.getOrDefault("copiedAt", ""),
                check,
                drill,
                filesPart);
    }

    private Path requireLocal(String host, String file) throws IOException {
        if (file == null || !FILE_NAME.matcher(file).matches()) {
            throw new IllegalArgumentException("Nom de sauvegarde invalide.");
        }
        Path dir = hostDirIfExists(host);
        Path local = dir == null ? null : dir.resolve(file);
        if (local == null || !Files.isRegularFile(local)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "la sauvegarde " + file + " n’est pas sur ce poste.", WHERE,
                    "clique « Copier la dernière » ou « Sauvegarder maintenant »."));
        }
        return local;
    }

    static String hostKey(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Indique le serveur.");
        }
        String key = host.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (key.matches("^\\.*$")) {
            throw new IllegalArgumentException("Serveur invalide : " + host);
        }
        return key;
    }

    private Path hostDirIfExists(String host) {
        Path dir = Path.of(properties.getStateDir()).resolve("backups").resolve(hostKey(host));
        return Files.isDirectory(dir) ? dir : null;
    }

    private Path hostDir(String host) throws IOException {
        Path dir = Path.of(properties.getStateDir()).resolve("backups").resolve(hostKey(host));
        Files.createDirectories(dir);
        return dir;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMeta(Path local) throws IOException {
        Path meta = metaPath(local);
        if (!Files.isRegularFile(meta)) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(json.readValue(meta.toFile(), Map.class));
    }

    private void writeMeta(Path local, Map<String, Object> meta) throws IOException {
        Path path = metaPath(local);
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), meta);
        ownerOnly(path);
    }

    private static Path metaPath(Path local) {
        return local.resolveSibling(local.getFileName() + ".json");
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void ownerOnly(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX : best effort
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // fichiers temporaires locaux
        }
    }

    private byte[] nextBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String excerpt(String raw) {
        String e = raw.replace('\n', ' ').strip();
        return e.length() > 240 ? e.substring(e.length() - 240) : e;
    }
}
