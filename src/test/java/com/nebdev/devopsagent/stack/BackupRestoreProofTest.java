package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sauvegarde réelle du lab (septembre 2026) produite par l’ancien rôle : mysqldump absent de mariadb:11.4,
 * l’erreur Docker avait été chiffrée à la place du dump. L’ancienne « preuve » l’acceptait ; elle doit être rejetée.
 */
class BackupRestoreProofTest {

    @Test
    void legacyBrokenLabBackupIsRejected() throws Exception {
        Path enc = Path.of("state/backups/dump-20260909T185957Z.sql.gz.enc");
        Path key = Path.of("state/backup.key");
        Assumptions.assumeTrue(Files.isRegularFile(enc) && Files.isRegularFile(key));
        BackupArchive.Verification v = BackupArchive.verify(enc, Files.readString(key).trim());
        assertThat(v.ok()).isFalse();
        assertThat(v.problem()).contains("pas de dump SQL complet");
    }
}
