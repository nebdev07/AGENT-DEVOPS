package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.config.DevopsProperties;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.SshTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Garde-fous de {@link BackupService} : aucun appel SSH ne doit partir (connecteurs absents). */
class BackupServiceTest {

    private static final SshTarget LAB = new SshTarget("198.51.100.20", 22, "ubuntu");
    private static final SshTarget READ_ONLY = new SshTarget("198.51.100.30", 22, "agent");

    @TempDir
    Path state;

    private BackupService service;

    @BeforeEach
    void setUp() {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(state.toString());
        service = new BackupService(props, new ActionGuard(),
                new ForbiddenTargetGuard(List.of("198.51.100.7"), List.of("198.51.100.30")),
                null, null, null);
    }

    @Test
    void runNowNeedsConfirmation() {
        assertThatThrownBy(() -> service.runNow(LAB, false)).isInstanceOf(UnsafeActionException.class);
    }

    @Test
    void readOnlyAndBlockedServersAreRefused() {
        assertThatThrownBy(() -> service.runNow(READ_ONLY, true)).isInstanceOf(UnsafeActionException.class);
        assertThatThrownBy(() -> service.fetchLatest(READ_ONLY)).isInstanceOf(UnsafeActionException.class);
        assertThatThrownBy(() -> service.drill(new SshTarget("198.51.100.7", 22, "x"), "dump-20261014T093751Z.sql.gz.enc", true))
                .isInstanceOf(UnsafeActionException.class);
    }

    @Test
    void drillAndRestoreNeedConfirmation() {
        assertThatThrownBy(() -> service.drill(LAB, "dump-20261014T093751Z.sql.gz.enc", false))
                .isInstanceOf(UnsafeActionException.class);
        assertThatThrownBy(() -> service.restorePlatform("shop", LAB, "dump-20261014T093751Z.sql.gz.enc", false, true))
                .isInstanceOf(UnsafeActionException.class);
    }

    @Test
    void fileNamesOutsideTheBackupPatternAreRejected() {
        assertThatThrownBy(() -> service.verify(LAB.host(), "../backup.key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.verify(LAB.host(), "dump-x.sql.gz.enc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.verify(LAB.host(), "dump-20261014T093751Z.sql.gz.enc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n’est pas sur ce poste");
    }

    @Test
    void hostKeyCannotEscapeTheBackupsFolder() {
        assertThat(BackupService.hostKey("../../etc")).doesNotContain("/").doesNotContain("\\");
        assertThat(BackupService.hostKey("57.128.186.38")).isEqualTo("57.128.186.38");
        assertThatThrownBy(() -> BackupService.hostKey("..")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passphraseIsCreatedOnceAndReused() throws Exception {
        String first = service.passphrase();
        assertThat(first).matches("[0-9a-f]{32}");
        assertThat(service.passphrase()).isEqualTo(first);
        assertThat(Files.readString(state.resolve("backup.key"))).isEqualTo(first);
    }

    @Test
    void listIsEmptyForUnknownServer() throws Exception {
        assertThat(service.list("203.0.113.9")).isEmpty();
    }

    @Test
    void knownRemoteFailuresBecomeOperatorMessages() {
        assertThatThrownBy(() -> BackupService.throwIfKnown("BACKUP_DUMP_FAILED\nDUMP_ERR: Access denied for user 'root'"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("export MariaDB a échoué")
                .hasMessageContaining("Access denied");
        assertThatThrownBy(() -> BackupService.throwIfKnown("NEED_BACKUP_ROLE"))
                .hasMessageContaining("pas installées");
        assertThatThrownBy(() -> BackupService.throwIfKnown("BACKUP_READY=/x\nBACKUP_FILES_FAILED\nFILES_ERR: No space left on device"))
                .hasMessageContaining("archive des fichiers a échoué")
                .hasMessageContaining("No space left");
    }

    @Test
    void filesArchiveSharesTheDumpTimestamp() {
        assertThat(BackupService.filesNameFor("dump-20261014T162526Z.sql.gz.enc"))
                .isEqualTo("files-20261014T162526Z.tar.gz.enc")
                .matches(BackupService.FILES_ARCHIVE_NAME.pattern());
        assertThatThrownBy(() -> BackupService.filesNameFor("../dump.sql")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void drillReportCoversTablesAndFiles() {
        String raw = "DRILL_DB=app_shop:25:25\nDRILL_VOL=plat_shop_uploads:12:12\nDRILL_DONE\n";
        assertThat(BackupService.drillLines(raw)).containsExactly(
                "app_shop : 25 table(s) restaurée(s) sur 25",
                "volume plat_shop_uploads : 12 fichier(s) restauré(s) sur 12");
        assertThat(BackupService.drillCountsMatch(raw)).isTrue();
    }

    @Test
    void drillFileGapOrMissingSpaceFails() {
        String gap = "DRILL_VOL=plat_shop_uploads:12:11\nDRILL_DONE\n";
        assertThat(BackupService.drillCountsMatch(gap)).isFalse();
        assertThat(BackupService.drillLines(gap)).singleElement().asString().contains("ÉCART");

        String noSpace = "DRILL_DB=app_shop:25:25\nDRILL_FILES_NO_SPACE=209715200:104857600\n";
        assertThat(BackupService.drillCountsMatch(noSpace)).isFalse();
        assertThat(BackupService.drillLines(noSpace)).last().asString()
                .contains("place insuffisante").contains("200 Mo").contains("100 Mo");
    }
}
