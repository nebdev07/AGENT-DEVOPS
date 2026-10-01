package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformRestoreTest {

    private static PlatformSpec platform() {
        return PlatformSpec.normalized("arc", "apps.exemple.com", "path", List.of(
                new PlatformServiceSpec("api", "https://github.com/org/api.git", "main", "", 8080, "",
                        List.of("mariadb", "volume:uploads"), Map.of(), "/arc-api"),
                new PlatformServiceSpec("www", "https://github.com/org/www.git", "main", "", 3000, "",
                        List.of(), Map.of(), "/")
        ), null);
    }

    @Test
    void remoteNamesAreFixedByExtension() {
        assertThat(PlatformRestoreService.sqlRemoteName("db-nwanyo-arc.SQL")).isEqualTo("dump.sql");
        assertThat(PlatformRestoreService.sqlRemoteName("prod.sql.gz")).isEqualTo("dump.sql.gz");
        assertThat(PlatformRestoreService.archiveRemoteName("uploads.tgz")).isEqualTo("uploads.tar.gz");
        assertThat(PlatformRestoreService.archiveRemoteName("files.zip")).isEqualTo("uploads.zip");
        assertThatThrownBy(() -> PlatformRestoreService.sqlRemoteName("dump.txt")).hasMessageContaining(".sql");
        assertThatThrownBy(() -> PlatformRestoreService.archiveRemoteName("x.rar")).hasMessageContaining(".tar.gz");
    }

    @Test
    void subdirIsSingleSafeSegment() {
        assertThat(PlatformRestoreService.normalizeSubdir(" /nwanyo/ ")).isEqualTo("nwanyo");
        assertThat(PlatformRestoreService.normalizeSubdir("")).isEmpty();
        assertThatThrownBy(() -> PlatformRestoreService.normalizeSubdir("../etc")).hasMessageContaining("invalide");
        assertThatThrownBy(() -> PlatformRestoreService.normalizeSubdir("a/b")).hasMessageContaining("invalide");
    }

    @Test
    void scriptCarriesVolumeServicesAndRejectsUnsafeDir() {
        String script = RoleScript.loadPlatformRestore(platform(), "/tmp/autopilot-restore-arc-0a1b2c3d",
                "dump.sql", "uploads.tar.gz", "nwanyo");
        assertThat(script).contains("UPLOADS_VOLUME=plat_arc_uploads");
        assertThat(script).contains("PLATFORM_SERVICES='api www'");
        assertThat(script).contains("PLATFORM_RESTORED");
        assertThat(script).contains("utf8mb4_unicode_ci");
        assertThatThrownBy(() -> RoleScript.loadPlatformRestore(platform(), "/etc", "dump.sql", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoleScript.loadPlatformRestore(platform(), "/tmp/autopilot-restore-arc-1",
                "../x.sql", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void composeVolumeHasFixedNameForRestoreBeforeDeploy() {
        String yaml = PlatformCompose.yaml(platform());
        assertThat(yaml).contains("plat_arc_uploads:/app/data");
        assertThat(yaml).contains("    name: plat_arc_uploads");
    }

    @Test
    void reportKeepsOnlyOperatorMarkers() {
        List<String> lines = PlatformRestoreService.reportLines(
                "noise\nDB_BACKUP=/opt/x.sql.gz\nSQL_RESTORED=app_arc\nTABLES=46\nFILES=120\nPLATFORM_RESTORED\n");
        assertThat(lines).containsExactly("DB_BACKUP=/opt/x.sql.gz", "SQL_RESTORED=app_arc", "TABLES=46", "FILES=120");
    }
}
