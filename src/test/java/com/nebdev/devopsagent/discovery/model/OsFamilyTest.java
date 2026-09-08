package com.nebdev.devopsagent.discovery.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OsFamilyTest {

    @Test
    void detectsPopularReleaseIds() {
        assertThat(OsFamily.fromOsReleaseId("ubuntu")).isEqualTo(OsFamily.UBUNTU);
        assertThat(OsFamily.fromOsReleaseId("debian")).isEqualTo(OsFamily.DEBIAN);
        assertThat(OsFamily.fromOsReleaseId("rocky")).isEqualTo(OsFamily.ROCKY);
        assertThat(OsFamily.fromOsReleaseId("almalinux")).isEqualTo(OsFamily.ALMA);
        assertThat(OsFamily.fromOsReleaseId("fedora")).isEqualTo(OsFamily.FEDORA);
        assertThat(OsFamily.fromOsReleaseId("alpine")).isEqualTo(OsFamily.ALPINE);
        assertThat(OsFamily.fromOsReleaseId("rhel")).isEqualTo(OsFamily.RHEL);
    }

    @Test
    void autoAcceptsAnyDetected() {
        assertThat(OsFamily.AUTO.compatibleWith(OsFamily.DEBIAN)).isTrue();
        assertThat(OsFamily.UBUNTU.compatibleWith(OsFamily.DEBIAN)).isFalse();
        assertThat(OsFamily.RHEL.compatibleWith(OsFamily.ROCKY)).isTrue();
    }
}
