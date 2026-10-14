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
    }
}
