package com.nebdev.devopsagent.discovery.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiveSnapshotTest {

    @Test
    void fixtureAndDocnetAreNotLive() {
        assertThat(LiveSnapshot.isLive(null)).isFalse();
        assertThat(LiveSnapshot.isLive(snap("fixture:x", "203.0.113.10"))).isFalse();
        assertThat(LiveSnapshot.isLive(snap("ssh:203.0.113.10", "203.0.113.10"))).isFalse();
        assertThat(LiveSnapshot.isLive(snap("ssh:57.128.186.38", "57.128.186.38"))).isTrue();
    }

    private static ServerSnapshot snap(String source, String host) {
        return new ServerSnapshot(
                null, source, host, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
    }
}
