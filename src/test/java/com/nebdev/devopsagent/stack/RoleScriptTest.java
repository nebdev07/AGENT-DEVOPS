package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

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
    void appRedeployChecksOutTheFetchedCommit() {
        String script = RoleScript.load("app");
        assertThat(script).contains("fetch --depth 1 origin \"$APP_REF\"")
                .contains("checkout -f FETCH_HEAD")
                .doesNotContain("checkout -f \"$APP_REF\"")
                .contains("APP_COMMIT=");
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
    void appRoleUsesInjectedGitDeployKey() {
        AppSpec spec = AppSpec.normalized("priv", "git@github.com:org/app.git", "main", "priv.example.com", 8080);
        String script = RoleScript.loadApp(spec, null, "QUJD");
        assertThat(script).contains("GIT_DEPLOY_KEY_B64=QUJD");
        assertThat(script).contains("git_as_deploy");
        assertThat(script).contains("NEED_GIT_DEPLOY_KEY");
        assertThat(script).contains("IdentitiesOnly=yes");
    }

    @Test
    void platformRoleHasNoPublishedPorts() {
        PlatformSpec p = PlatformSpec.normalized(
                "ma-asso",
                "apps.exemple.com",
                "host",
                List.of(
                        new PlatformServiceSpec(
                                "api",
                                "https://github.com/org/api.git",
                                "main",
                                "",
                                8080,
                                "",
                                List.of("mariadb", "redis"),
                                Map.of(),
                                ""
                        ),
                        new PlatformServiceSpec(
                                "www",
                                "https://github.com/org/site.git",
                                "main",
                                "",
                                3000,
                                "",
                                List.of(),
                                Map.of("NEXT_PUBLIC_API_URL", "https://api.ma-asso.apps.exemple.com"),
                                ""
                        )
                ),
                null
        );
        String script = RoleScript.loadPlatform(p);
        assertThat(script).doesNotContain("\r");
        assertThat(script).contains("PLATFORM_ID=ma-asso");
        assertThat(script).contains("PLATFORM_NEED_DB=1");
        assertThat(script).contains("PLATFORM_NEED_REDIS=1");
        assertThat(script).contains("PLATFORM_DEPLOYED");
        String b64 = java.util.Arrays.stream(script.split("\n"))
                .filter(l -> l.startsWith("PLATFORM_COMPOSE_B64="))
                .findFirst()
                .orElseThrow();
        String yaml = new String(Base64.getDecoder().decode(b64.substring("PLATFORM_COMPOSE_B64=".length())));
        assertThat(yaml).contains("traefik.enable=true");
        assertThat(yaml).doesNotContain("8080:8080");
        assertThat(yaml).doesNotContain("3000:3000");
        assertThat(yaml).contains("Host(`api.ma-asso.apps.exemple.com`)");
        assertThat(yaml).contains("NEXT_PUBLIC_API_URL");
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
