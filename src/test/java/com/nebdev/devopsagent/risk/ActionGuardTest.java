package com.nebdev.devopsagent.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionGuardTest {

    private final ActionGuard guard = new ActionGuard();

    @Test
    void allowsRead() {
        assertThatCode(() -> guard.assertAllowed(RiskLevel.READ, "discover"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsChangeAndDestructiveWithoutConfirmPath() {
        assertThatThrownBy(() -> guard.assertAllowed(RiskLevel.CHANGE, "install-docker"))
                .isInstanceOf(UnsafeActionException.class)
                .hasMessageContaining("CHANGE");
        assertThatThrownBy(() -> guard.assertAllowed(RiskLevel.DESTRUCTIVE, "drop-database"))
                .isInstanceOf(UnsafeActionException.class);
    }

    @Test
    void confirmedWhitelistedChangePasses() {
        assertThatCode(() -> guard.assertConfirmedChange("install-agent-pubkey", true))
                .doesNotThrowAnyException();
    }

    @Test
    void changeWithoutConfirmIsRejected() {
        assertThatThrownBy(() -> guard.assertConfirmedChange("install-agent-pubkey", false))
                .isInstanceOf(UnsafeActionException.class)
                .hasMessageContaining("non confirmé");
    }

    @Test
    void unknownChangeIsRejectedEvenIfConfirmed() {
        assertThatThrownBy(() -> guard.assertConfirmedChange("install-unlisted", true))
                .isInstanceOf(UnsafeActionException.class)
                .hasMessageContaining("non whitelisté");
    }

    @Test
    void confirmedSecurityAndDockerPass() {
        assertThatCode(() -> guard.assertConfirmedChange("install-security", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertConfirmedChange("install-docker", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertConfirmedChange("install-traefik", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertConfirmedChange("install-mariadb", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertConfirmedChange("install-deploy", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertConfirmedChange("install-redis", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertConfirmedChange("install-backups", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertConfirmedChange("deploy-app", true))
                .doesNotThrowAnyException();
    }
}
