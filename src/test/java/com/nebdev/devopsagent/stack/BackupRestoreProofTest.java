package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRestoreProofTest {

    @Test
    void decryptsExistingEncIfPossible() throws Exception {
        Path enc = Path.of("state/backups/dump-20260909T185957Z.sql.gz.enc");
        Path key = Path.of("state/backup.key");
        Assumptions.assumeTrue(Files.isRegularFile(enc) && Files.isRegularFile(key));
        String pass = Files.readString(key).trim();
        assertThat(InstallService.verifyRestoreProof(enc, pass)).isTrue();
    }
}