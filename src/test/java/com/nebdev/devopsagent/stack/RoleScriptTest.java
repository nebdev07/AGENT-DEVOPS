package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoleScriptTest {

    @Test
    void securityRoleHasUnixNewlines() {
        String script = RoleScript.load("security");
        assertThat(script).doesNotContain("\r");
        assertThat(script).startsWith("set -eu\n");
        assertThat(script).contains("SECURITY_APPLIED");
        assertThat(script).contains("00-vps-autopilot.conf");
        assertThat(script).contains("rm -f /etc/ssh/sshd_config.d/99-vps-autopilot.conf");
    }

    @Test
    void traefikRoleIsVersionedAndUnix() {
        String script = RoleScript.load("traefik");
        assertThat(script).doesNotContain("\r");
        assertThat(script).contains("TRAEFIK_APPLIED");
        assertThat(script).contains("exposedbydefault=false");
        assertThat(script).contains("/opt/vps-autopilot/traefik");
        assertThat(script).contains("traefik:v3.5");
        assertThat(script).contains("DOCKER_API_VERSION=1.44");
        assertThat(script).contains("min-api-version");
    }

    @Test
    void mariadbRoleIsVersionedInternalOnly() {
        String script = RoleScript.load("mariadb");
        assertThat(script).doesNotContain("\r");
        assertThat(script).contains("MARIADB_APPLIED");
        assertThat(script).doesNotContain("3306:3306");
        assertThat(script).contains("/opt/vps-autopilot/mariadb");
    }

    @Test
    void deployRoleCreatesNonRootDockerUser() {
        String script = RoleScript.load("deploy");
        assertThat(script).doesNotContain("\r");
        assertThat(script).contains("DEPLOY_APPLIED");
        assertThat(script).contains("useradd");
        assertThat(script).contains("passwd -l");
        assertThat(script).contains("AllowUsers");
        assertThat(script).doesNotContain("NOPASSWD");
    }

    @Test
    void appRoleBuildsWithoutPublishedPort() {
        AppSpec spec = AppSpec.normalized("demo", "https://example.com/org/app.git", "main", "demo.example.com", 8080);
        String script = RoleScript.loadApp(spec);
        assertThat(script).doesNotContain("\r");
        assertThat(script).contains("APP_SLUG=demo");
        assertThat(script).contains("APP_DEPLOYED");
        assertThat(script).contains("traefik.enable=true");
        assertThat(script).doesNotContain("8080:8080");
        assertThat(script).contains("Dockerfile");
        assertThat(script).contains("DB_GRANT");
        assertThat(script).contains("db.env");
    }

    @Test
    void backupsRoleFlagsEncryptedDumpAndOffsite() {
        String script = RoleScript.load("backups", List.of("encrypted-dump", "offsite"));
        assertThat(script).doesNotContain("\r");
        assertThat(script).contains("APPLY_ENCRYPTED_DUMP=1");
        assertThat(script).contains("APPLY_OFFSITE=1");
        assertThat(script).contains("APPLY_RESTORE_PROOF=0");
        assertThat(script).contains("BACKUPS_APPLIED");
        assertThat(script).contains("BACKUP_READY");
        assertThat(script).doesNotContain("BACKUP_PASSPHRASE=");
    }
}
