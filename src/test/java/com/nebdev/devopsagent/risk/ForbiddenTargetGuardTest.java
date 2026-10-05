package com.nebdev.devopsagent.risk;

import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForbiddenTargetGuardTest {

    private final ForbiddenTargetGuard guard = new ForbiddenTargetGuard(
            List.of("198.51.100.7", "prod.example.org"),
            List.of("192.0.2.44"));

    @Test
    void allowsBlankAndUnlistedHosts() {
        assertThatCode(() -> guard.assertAllowed("")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertAllowed("203.0.113.10")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadAllowed("203.0.113.10")).doesNotThrowAnyException();
    }

    @Test
    void blockedHostsRefuseReadsAndChanges() {
        assertThatThrownBy(() -> guard.assertAllowed("198.51.100.7"))
                .isInstanceOf(UnsafeActionException.class);
        assertThatThrownBy(() -> guard.assertReadAllowed(" PROD.EXAMPLE.ORG "))
                .isInstanceOf(UnsafeActionException.class);
    }

    @Test
    void readOnlyHostsAllowOnlyReads() {
        assertThatCode(() -> guard.assertReadAllowed("192.0.2.44")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.assertAllowed("192.0.2.44"))
                .isInstanceOf(UnsafeActionException.class)
                .hasMessageContaining("lecture seule");
    }

    @Test
    void listsComeFromOperatorProperties() {
        DevopsProperties props = new DevopsProperties();
        props.getGuard().setBlockedHosts(List.of("198.51.100.7"));
        props.getGuard().setReadOnlyHosts(List.of("192.0.2.44"));
        ForbiddenTargetGuard fromProps = new ForbiddenTargetGuard(props);
        assertThatThrownBy(() -> fromProps.assertReadAllowed("198.51.100.7"))
                .isInstanceOf(UnsafeActionException.class);
        assertThatThrownBy(() -> fromProps.assertAllowed("192.0.2.44"))
                .isInstanceOf(UnsafeActionException.class);
        assertThatCode(() -> new ForbiddenTargetGuard().assertAllowed("198.51.100.7"))
                .doesNotThrowAnyException();
    }
}
