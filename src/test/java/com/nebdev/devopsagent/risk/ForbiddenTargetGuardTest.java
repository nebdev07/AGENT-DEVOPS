package com.nebdev.devopsagent.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForbiddenTargetGuardTest {

    private final ForbiddenTargetGuard guard = new ForbiddenTargetGuard();

    @Test
    void allowsBlankAndLabIp() {
        assertThatCode(() -> guard.assertAllowed("")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertAllowed("203.0.113.10")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadAllowed("203.0.113.10")).doesNotThrowAnyException();
    }

    @Test
    void blocksArcHostsForReadsAndChanges() {
        assertThatThrownBy(() -> guard.assertAllowed("54.37.156.11"))
                .isInstanceOf(UnsafeActionException.class);
        assertThatThrownBy(() -> guard.assertAllowed("arc-nwanyo.org"))
                .isInstanceOf(UnsafeActionException.class);
        assertThatThrownBy(() -> guard.assertReadAllowed("54.37.156.11"))
                .isInstanceOf(UnsafeActionException.class);
        assertThatThrownBy(() -> guard.assertReadAllowed(" ARC-NWANYO.ORG "))
                .isInstanceOf(UnsafeActionException.class);
    }

    @Test
    void dtsiServerIsReadOnly() {
        assertThatCode(() -> guard.assertReadAllowed("154.0.30.233")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.assertAllowed("154.0.30.233"))
                .isInstanceOf(UnsafeActionException.class)
                .hasMessageContaining("lecture seule");
    }
}
