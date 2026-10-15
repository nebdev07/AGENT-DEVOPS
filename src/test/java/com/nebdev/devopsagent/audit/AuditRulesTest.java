package com.nebdev.devopsagent.audit;

import com.nebdev.devopsagent.audit.AuditReport.Finding;
import com.nebdev.devopsagent.audit.AuditReport.Severity;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.BackupFile;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.Hygiene;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot.ListeningPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final long GB = 1024L * 1024 * 1024;

    private static ServerSnapshot snap(
            String os, String version, String pwd, String root, String ufw, String f2b,
            List<ListeningPort> ports, Hygiene hygiene, boolean docker, boolean mariadb, long diskUsedGb
    ) {
        ServerSnapshot.SoftwarePresence on = new ServerSnapshot.SoftwarePresence(true, null, "PRESENT");
        ServerSnapshot.SoftwarePresence off = new ServerSnapshot.SoftwarePresence(false, null, "ABSENT");
        return new ServerSnapshot(
                NOW, "ssh:198.51.100.20", "198.51.100.20", "vps-test", "", "auto", os, os, version,
                os + " " + version, "Linux 6.8", "x86_64",
                new ServerSnapshot.CpuInfo(4, "EPYC"),
                new ServerSnapshot.MemoryInfo(8 * GB, 4 * GB),
                new ServerSnapshot.DiskInfo(100 * GB, diskUsedGb * GB, "/"),
                List.of("198.51.100.20"), ports, List.of(),
                docker ? on : off, off, mariadb ? on : off, off, docker ? on : off, off, off,
                List.of(),
                new ServerSnapshot.SshDaemonInfo(true, root, pwd, 22),
                new ServerSnapshot.FirewallInfo("ufw", ufw, f2b, List.of()),
                "root", List.of(), hygiene);
    }

    private static Hygiene hygiene(Integer pending, Integer security, boolean reboot, Boolean auto,
                                   Boolean timer, List<BackupFile> backups, List<String> signals) {
        return new Hygiene(pending, security, reboot, auto, 1000L, timer, backups, signals);
    }

    private static List<String> ids(AuditReport r) {
        return r.findings().stream().map(Finding::id).toList();
    }

    @Test
    void hardenedServerWithFreshBackupScoresA() {
        BackupFile fresh = new BackupFile("dump.sql.gz.enc", NOW.minusSeconds(6 * 3600).getEpochSecond(), 5_000_000);
        AuditReport r = AuditRules.evaluate(snap("ubuntu", "24.04", "no", "no", "active", "active",
                List.of(new ListeningPort("tcp", "0.0.0.0", 22), new ListeningPort("tcp", "0.0.0.0", 443),
                        new ListeningPort("tcp", "127.0.0.1", 3306)),
                hygiene(0, 0, false, true, true, List.of(fresh), List.of()), true, true, 30));

        assertThat(r.findings()).isEmpty();
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.grade()).isEqualTo("A");
        assertThat(r.strengths()).anyMatch(s -> s.contains("clé uniquement"))
                .anyMatch(s -> s.contains("48 h"))
                .anyMatch(s -> s.contains("conteneurs"));
        assertThat(r.facts().publicPorts()).isEqualTo("22, 443");
    }

    private static AuditReport withDrill(Boolean drillTimer, ServerSnapshot.AutoDrill drill) {
        BackupFile fresh = new BackupFile("dump.sql.gz.enc", NOW.minusSeconds(6 * 3600).getEpochSecond(), 5_000_000);
        Hygiene h = new Hygiene(0, 0, false, true, 1000L, true, List.of(fresh), List.of(), drillTimer, drill);
        return AuditRules.evaluate(snap("ubuntu", "24.04", "no", "no", "active", "active",
                List.of(new ListeningPort("tcp", "0.0.0.0", 22)), h, true, true, 30));
    }

    @Test
    void automaticRestoreDrillIsJudged() {
        assertThat(ids(withDrill(null, null))).doesNotContain("restore-drill-missing");

        assertThat(ids(withDrill(true, null))).containsExactly("restore-drill-missing");

        AuditReport old = withDrill(true, new ServerSnapshot.AutoDrill("2026-08-01T04:00:00Z", true, "d", List.of()));
        assertThat(old.findings().get(0).title()).contains("73 jours");

        AuditReport recent = withDrill(true, new ServerSnapshot.AutoDrill("2026-10-01T04:00:00Z", true, "d",
                List.of("DRILL_DB=app_arc:25:25")));
        assertThat(recent.findings()).isEmpty();
        assertThat(recent.strengths()).anyMatch(s -> s.contains("restaurée avec succès le 1 octobre 2026"));

        AuditReport failed = withDrill(true, new ServerSnapshot.AutoDrill("2026-10-01T04:00:00Z", false, "d",
                List.of("DRILL_DB=app_arc:25:24")));
        assertThat(failed.findings()).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo("restore-drill-failed");
            assertThat(f.severity()).isEqualTo(Severity.ELEVE);
            assertThat(f.observed()).contains("app_arc:25:24");
        });
    }

    private static AuditReport withAlerts(ServerSnapshot.Notify notify) {
        BackupFile fresh = new BackupFile("dump.sql.gz.enc", NOW.minusSeconds(6 * 3600).getEpochSecond(), 5_000_000);
        Hygiene h = new Hygiene(0, 0, false, true, 1000L, true, List.of(fresh), List.of(), null, null, notify);
        return AuditRules.evaluate(snap("ubuntu", "24.04", "no", "no", "active", "active",
                List.of(new ListeningPort("tcp", "0.0.0.0", 22)), h, true, true, 30));
    }

    @Test
    void emailAlertsAreJudged() {
        assertThat(ids(withAlerts(null))).isEmpty();

        assertThat(withAlerts(new ServerSnapshot.Notify(false, null, null, null)).findings()).singleElement()
                .satisfies(f -> {
                    assertThat(f.id()).isEqualTo("alerts-missing");
                    assertThat(f.severity()).isEqualTo(Severity.FAIBLE);
                });

        assertThat(withAlerts(new ServerSnapshot.Notify(true, "2026-10-15T09:00:00Z", "failure", false)).findings())
                .singleElement().satisfies(f -> assertThat(f.id()).isEqualTo("alerts-failing"));

        AuditReport ok = withAlerts(new ServerSnapshot.Notify(true, "2026-10-15T09:00:00Z", "test", true));
        assertThat(ok.findings()).isEmpty();
        assertThat(ok.strengths()).anyMatch(s -> s.contains("Alerte e-mail automatique"));
    }

    @Test
    void typicalHandMadeServerIsRankedAndSortedBySeverity() {
        AuditReport r = AuditRules.evaluate(snap("ubuntu", "20.04", "yes", "yes", "inactive", "absent",
                List.of(new ListeningPort("tcp", "0.0.0.0", 3306), new ListeningPort("tcp", "[::]", 6379),
                        new ListeningPort("tcp", "*", 8080)),
                hygiene(40, 25, true, false, false, List.of(), List.of("APACHE_PRESENT", "MARIADB_HOST")),
                false, false, 92));

        assertThat(ids(r)).contains("ssh-password", "ssh-root", "firewall", "fail2ban", "port-3306", "port-6379",
                "port-8080", "os-eol", "security-updates", "reboot", "auto-updates", "no-backup", "disk", "host-stack");
        assertThat(r.findings().get(0).severity()).isEqualTo(Severity.CRITIQUE);
        assertThat(r.findings().get(0).id()).isEqualTo("port-6379");
        assertThat(r.findings()).filteredOn(f -> f.id().equals("no-backup"))
                .allMatch(f -> f.severity() == Severity.ELEVE);
        assertThat(r.score()).isZero();
        assertThat(r.grade()).isEqualTo("E");
    }

    @Test
    void activeFirewallLowersPortExposureOneLevel() {
        AuditReport r = AuditRules.evaluate(snap("ubuntu", "24.04", "no", "no", "active", "active",
                List.of(new ListeningPort("tcp", "0.0.0.0", 6379)),
                hygiene(0, 0, false, true, true,
                        List.of(new BackupFile("d.enc", NOW.getEpochSecond(), 9_000_000)), List.of()),
                true, true, 10));

        Finding redis = r.findings().get(0);
        assertThat(redis.severity()).isEqualTo(Severity.ELEVE);
        assertThat(redis.observed()).contains("Docker le contournent");
    }

    @Test
    void emptyEncryptedBackupIsFlaggedEvenWhenRecent() {
        BackupFile tiny = new BackupFile("dump.sql.gz.enc", NOW.minusSeconds(3600).getEpochSecond(), 144);
        AuditReport r = AuditRules.evaluate(snap("ubuntu", "24.04", "no", "no", "active", "active", List.of(),
                hygiene(0, 0, false, true, false, List.of(tiny), List.of()), true, true, 10));

        assertThat(ids(r)).contains("backup-empty", "backup-schedule").doesNotContain("backup-old", "no-backup");
        assertThat(r.strengths()).noneMatch(s -> s.contains("48 h"));
    }

    @Test
    void smallFilesArchiveNextToRealDumpIsNotFlagged() {
        BackupFile dump = new BackupFile("dump-20261014T163000Z.sql.gz.enc", NOW.minusSeconds(3600).getEpochSecond(), 4_300_000);
        BackupFile files = new BackupFile("files-20261014T163000Z.tar.gz.enc", NOW.minusSeconds(3590).getEpochSecond(), 320);
        AuditReport r = AuditRules.evaluate(snap("ubuntu", "24.04", "no", "no", "active", "active", List.of(),
                hygiene(0, 0, false, true, true, List.of(dump, files), List.of()), true, true, 10));

        assertThat(ids(r)).doesNotContain("backup-empty", "backup-old", "no-backup");
    }

    @Test
    void criticalFindingCapsGradeAtD() {
        BackupFile ok = new BackupFile("d.enc", NOW.getEpochSecond(), 9_000_000);
        AuditReport r = AuditRules.evaluate(snap("ubuntu", "24.04", "no", "no", "active", "active", List.of(),
                hygiene(0, 0, false, true, true, List.of(ok), List.of("MINER_PROCESS")), true, true, 10));

        assertThat(r.score()).isEqualTo(70);
        assertThat(r.grade()).isEqualTo("D");
    }

    @Test
    void privateAndLoopbackAddressesAreNotPublic() {
        assertThat(AuditRules.isPublic("127.0.0.53%lo")).isFalse();
        assertThat(AuditRules.isPublic("[::1]")).isFalse();
        assertThat(AuditRules.isPublic("10.0.0.5")).isFalse();
        assertThat(AuditRules.isPublic("172.18.0.1")).isFalse();
        assertThat(AuditRules.isPublic("192.168.1.10")).isFalse();
        assertThat(AuditRules.isPublic("172.32.0.1")).isTrue();
        assertThat(AuditRules.isPublic("0.0.0.0")).isTrue();
        assertThat(AuditRules.isPublic("[::]")).isTrue();
        assertThat(AuditRules.isPublic("198.51.100.20")).isTrue();
    }

    @Test
    void olderDiscoveryWithoutHygieneStillProducesAReport() {
        AuditReport r = AuditRules.evaluate(snap("debian", "11", "no", "prohibit-password", "active", "active",
                List.of(), null, true, true, 10));

        assertThat(ids(r)).containsExactly("os-eol");
        assertThat(r.grade()).isEqualTo("B");
    }
}
