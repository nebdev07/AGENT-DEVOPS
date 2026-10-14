package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformRestoreTest {

    private static PlatformSpec platform() {
        return PlatformSpec.normalized("demo", "apps.exemple.com", "path", List.of(
                new PlatformServiceSpec("api", "https://github.com/org/api.git", "main", "", 8080, "",
                        List.of("mariadb", "volume:uploads"), Map.of(), "/demo-api"),
                new PlatformServiceSpec("www", "https://github.com/org/www.git", "main", "", 3000, "",
                        List.of(), Map.of(), "/")
        ), null);
    }

    @Test
    void remoteNamesAreFixedByExtension() {
        assertThat(PlatformRestoreService.sqlRemoteName("db-demo.SQL")).isEqualTo("dump.sql");
        assertThat(PlatformRestoreService.sqlRemoteName("prod.sql.gz")).isEqualTo("dump.sql.gz");
        assertThat(PlatformRestoreService.archiveRemoteName("uploads.tgz")).isEqualTo("uploads.tar.gz");
        assertThat(PlatformRestoreService.archiveRemoteName("files.zip")).isEqualTo("uploads.zip");
        assertThatThrownBy(() -> PlatformRestoreService.sqlRemoteName("dump.txt")).hasMessageContaining(".sql");
        assertThatThrownBy(() -> PlatformRestoreService.archiveRemoteName("x.rar")).hasMessageContaining(".tar.gz");
        assertThat(PlatformRestoreService.archiveRemoteName("files-20261014T162526Z.tar.gz.enc"))
                .isEqualTo("uploads.tar.gz.enc");
    }

    @Test
    void agentFilesBackupReplacesTheWholeVolumeWithRollback() {
        String script = RoleScript.loadPlatformRestore(platform(), "/tmp/autopilot-restore-demo-0a1b2c3d",
                null, "uploads.tar.gz.enc", null);
        assertThat(script).contains("*.tar.gz.enc)");
        assertThat(script).contains("--strip-components=2");
        assertThat(script).contains("UPLOADS_ROLLED_BACK=");
        assertThat(script).contains("NEED_BACKUP_PASSPHRASE");
        assertThatThrownBy(() -> PlatformRestoreService.throwIfKnown(
                "UPLOADS_BACKUP=/opt/u.tar.gz\nUPLOADS_EXTRACT_FAILED\nUPLOADS_ROLLED_BACK=/opt/u.tar.gz\n"))
                .hasMessageContaining("fichiers d’avant ont été remis");
    }

    @Test
    void drillScriptCarriesFilesManifestAndRejectsUnsafeVolume() {
        String dir = "/tmp/autopilot-restore-drill-0a1b2c3d";
        String script = RoleScript.loadBackupDrill(dir, Map.of(), "files.tar.gz.enc",
                Map.of("plat_demo_uploads", 12), 4096);
        assertThat(script).contains("DRILL_FILES=files.tar.gz.enc\n").contains("DRILL_FILES_BYTES=4096\n")
                .contains("DRILL_MANIFEST_B64=\n").contains("/var/tmp/autopilot-files-drill-");
        assertThatThrownBy(() -> RoleScript.loadBackupDrill(dir, Map.of(), "files.tar.gz.enc",
                Map.of("../etc", 1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subdirIsSingleSafeSegment() {
        assertThat(PlatformRestoreService.normalizeSubdir(" /medias/ ")).isEqualTo("medias");
        assertThat(PlatformRestoreService.normalizeSubdir("")).isEmpty();
        assertThatThrownBy(() -> PlatformRestoreService.normalizeSubdir("../etc")).hasMessageContaining("invalide");
        assertThatThrownBy(() -> PlatformRestoreService.normalizeSubdir("a/b")).hasMessageContaining("invalide");
    }

    @Test
    void scriptCarriesVolumeServicesAndRejectsUnsafeDir() {
        String script = RoleScript.loadPlatformRestore(platform(), "/tmp/autopilot-restore-demo-0a1b2c3d",
                "dump.sql", "uploads.tar.gz", "medias");
        assertThat(script).contains("UPLOADS_VOLUME=plat_demo_uploads");
        assertThat(script).contains("PLATFORM_SERVICES='api www'");
        assertThat(script).contains("PLATFORM_RESTORED");
        assertThat(script).contains("utf8mb4_unicode_ci");
        assertThatThrownBy(() -> RoleScript.loadPlatformRestore(platform(), "/etc", "dump.sql", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoleScript.loadPlatformRestore(platform(), "/tmp/autopilot-restore-demo-1",
                "../x.sql", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void composeVolumeHasFixedNameForRestoreBeforeDeploy() {
        String yaml = PlatformCompose.yaml(platform());
        assertThat(yaml).contains("plat_demo_uploads:/app/data");
        assertThat(yaml).contains("    name: plat_demo_uploads");
    }

    @Test
    void reportKeepsOnlyOperatorMarkers() {
        List<String> lines = PlatformRestoreService.reportLines(
                "noise\nDB_BACKUP=/opt/x.sql.gz\nSQL_RESTORED=app_arc\nTABLES=46\nFILES=120\nPLATFORM_RESTORED\n");
        assertThat(lines).containsExactly("DB_BACKUP=/opt/x.sql.gz", "SQL_RESTORED=app_arc", "TABLES=46", "FILES=120");
    }

    @Test
    void foreignKeyChecksAreOnByDefaultAndCanBeTurnedOff() {
        String dir = "/tmp/autopilot-restore-demo-0a1b2c3d";
        assertThat(RoleScript.loadPlatformRestore(platform(), dir, "dump.sql", null, null)).contains("RESTORE_FK_CHECKS=1\n");
        String off = RoleScript.loadPlatformRestore(platform(), dir, "dump.sql", null, null, false);
        assertThat(off).contains("RESTORE_FK_CHECKS=0\n");
        assertThat(off).contains("SET FOREIGN_KEY_CHECKS=0;");
    }

    @Test
    void failedImportServicesAlwaysRestartAndDatabaseRollsBack() {
        String script = RoleScript.loadPlatformRestore(platform(), "/tmp/autopilot-restore-demo-0a1b2c3d", "dump.sql", null, null);
        assertThat(script).contains("trap finish EXIT");
        assertThat(script).contains("DB_ROLLED_BACK=");
        assertThat(script).contains(".maintenance");
    }

    @Test
    void foreignKeyErrorSuggestsTurningChecksOff() {
        String raw = "SERVICES_STOPPED=api www\nDB_BACKUP=/opt/b.sql.gz\nSQL_IMPORT_FAILED\n--------------\n"
                + "ERROR 1452 (23000) at line 5694: Cannot add or update a child row\nDB_ROLLED_BACK=/opt/b.sql.gz\n";
        assertThat(PlatformRestoreService.importError(raw)).startsWith("ERROR 1452");
        assertThatThrownBy(() -> PlatformRestoreService.throwIfKnown(raw))
                .hasMessageContaining("remise dans son état d’avant")
                .hasMessageContaining("Vérifier les clés étrangères");
    }
}
