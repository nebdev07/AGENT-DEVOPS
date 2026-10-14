package com.nebdev.devopsagent.discovery;

import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryParserTest {

    private final DiscoveryParser parser = new DiscoveryParser();

    @Test
    void parsesBlankUbuntuVps2Fixture() throws Exception {
        String raw = new String(
                Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/ubuntu-vps2-blank.txt")).readAllBytes(),
                StandardCharsets.UTF_8);

        ServerSnapshot snap = parser.parse(raw, "fixture", "203.0.113.10");

        assertThat(snap.osId()).isEqualTo("ubuntu");
        assertThat(snap.osFamily()).isEqualTo("ubuntu");
        assertThat(snap.osVersion()).isEqualTo("24.04");
        assertThat(snap.osPretty()).contains("Ubuntu 24.04");
        assertThat(snap.arch()).isEqualTo("x86_64");
        assertThat(snap.cpu().vcpus()).isEqualTo(4);
        assertThat(snap.cpu().model()).contains("EPYC");
        assertThat(snap.memory().totalBytes()).isEqualTo(8142848L * 1024);
        assertThat(snap.disk().mount()).isEqualTo("/");
        assertThat(snap.disk().totalBytes()).isEqualTo(80530636800L);
        assertThat(snap.ipAddresses()).containsExactly("203.0.113.10");
        assertThat(snap.docker().present()).isFalse();
        assertThat(snap.nginx().present()).isFalse();
        assertThat(snap.mariadb().present()).isFalse();
        assertThat(snap.redis().present()).isFalse();
        assertThat(snap.traefik().present()).isFalse();
        assertThat(snap.deploy().present()).isFalse();
        assertThat(snap.backups().present()).isFalse();
        assertThat(snap.apps()).isEmpty();
        assertThat(snap.ssh().listening()).isTrue();
        assertThat(snap.ssh().permitRootLogin()).isEqualTo("yes");
        assertThat(snap.ssh().passwordAuthentication()).isEqualTo("yes");
        assertThat(snap.firewall().ufwStatus()).isEqualTo("inactive");
        assertThat(snap.firewall().fail2banStatus()).isEqualTo("absent");
        assertThat(snap.firewall().listeningTcpPorts()).contains(22);
        assertThat(snap.runningServices()).contains("ssh.service");
        assertThat(snap.remoteUser()).isEqualTo("root");
        assertThat(snap.warnings()).isEmpty();
    }

    @Test
    void dockerPresentIsIdempotentSignal() {
        String raw = """
                ###DOCKER###
                PRESENT
                Docker version 27.3.1, build 41ca978
                Docker Compose version v2.29.7
                27.3.1
                ###TRAEFIK###
                PRESENT
                """;
        ServerSnapshot snap = parser.parse(raw, "fixture", "lab");
        assertThat(snap.docker().present()).isTrue();
        assertThat(snap.docker().version()).contains("27.3.1");
        assertThat(snap.docker().detail()).contains("Docker Compose version");
        assertThat(snap.traefik().present()).isTrue();
    }

    @Test
    void deployUserPresentWhenInDockerGroup() {
        String raw = """
                ###DEPLOY###
                PRESENT
                deploy docker
                """;
        ServerSnapshot snap = parser.parse(raw, "fixture", "lab");
        assertThat(snap.deploy().present()).isTrue();
    }

    @Test
    void runningAppsAreListed() {
        String raw = """
                ###APPS###
                demo
                autre
                """;
        ServerSnapshot snap = parser.parse(raw, "fixture", "lab");
        assertThat(snap.apps()).containsExactly("demo", "autre");
    }

    @Test
    void fail2banActiveIsDetected() {
        String raw = """
                ###FAIL2BAN###
                ACTIVE
                ###SERVICES###
                ssh.service
                """;
        ServerSnapshot snap = parser.parse(raw, "ssh:198.51.100.20", "198.51.100.20");
        assertThat(snap.firewall().fail2banStatus()).isEqualTo("active");
    }

    @Test
    void hygieneSectionIsParsed() {
        String raw = """
                ###SIGNALS###
                APACHE_PRESENT
                ###HYGIENE###
                APT_CHECK=12;3
                REBOOT_REQUIRED
                AUTO_UPDATES=off
                UPTIME=86400
                BACKUP_TIMER=on
                BACKUP_FILE=1791900000 524288 dump-20261013T020000Z.sql.gz.enc
                BACKUP_FILE=garbage
                ###WHO###
                root
                """;
        ServerSnapshot.Hygiene h = parser.parse(raw, "ssh:198.51.100.20", "198.51.100.20").hygiene();
        assertThat(h.pendingUpdates()).isEqualTo(12);
        assertThat(h.securityUpdates()).isEqualTo(3);
        assertThat(h.rebootRequired()).isTrue();
        assertThat(h.autoUpdates()).isFalse();
        assertThat(h.uptimeSeconds()).isEqualTo(86400L);
        assertThat(h.backupTimer()).isTrue();
        assertThat(h.backupFiles()).containsExactly(
                new ServerSnapshot.BackupFile("dump-20261013T020000Z.sql.gz.enc", 1791900000L, 524288L));
        assertThat(h.signals()).containsExactly("APACHE_PRESENT");
        assertThat(h.drillTimer()).isNull();
        assertThat(h.autoDrill()).isNull();
    }

    @Test
    void automaticRestoreDrillIsParsed() {
        String raw = """
                ###HYGIENE###
                BACKUP_TIMER=on
                DRILL_TIMER=on
                AUTO_DRILL_AT=2026-10-01T04:12:00Z
                AUTO_DRILL_DUMP=dump-20261001T023000Z.sql.gz.enc
                AUTO_DRILL_DB=app_arc:25:25
                AUTO_DRILL_VOL=plat_arc_uploads:6:6
                AUTO_DRILL_OK=1
                ###WHO###
                root
                """;
        ServerSnapshot.Hygiene h = parser.parse(raw, "ssh:198.51.100.20", "198.51.100.20").hygiene();
        assertThat(h.drillTimer()).isTrue();
        assertThat(h.autoDrill()).isEqualTo(new ServerSnapshot.AutoDrill("2026-10-01T04:12:00Z", true,
                "dump-20261001T023000Z.sql.gz.enc",
                List.of("DRILL_DB=app_arc:25:25", "DRILL_VOL=plat_arc_uploads:6:6")));
    }

    @Test
    void hygieneIsNullForOlderDiscoveries() {
        assertThat(parser.parse("###WHO###\nroot\n", "fixture", "lab").hygiene()).isNull();
    }

    @Test
    void minerAndPhpSignalsBecomeWarnings() {
        String raw = """
                ###SIGNALS###
                PHP_PRESENT
                MINER_PROCESS
                """;
        ServerSnapshot snap = parser.parse(raw, "ssh:198.51.100.20", "198.51.100.20");
        assertThat(snap.warnings()).anyMatch(w -> w.contains("miner"));
        assertThat(snap.warnings()).anyMatch(w -> w.contains("PHP"));
    }
}
