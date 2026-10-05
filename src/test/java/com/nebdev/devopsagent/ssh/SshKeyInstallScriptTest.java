package com.nebdev.devopsagent.ssh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshKeyInstallScriptTest {

    private static final String KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIL8p88ZMRkkFRXsBmwLMlie6HN4X1tG3G7Jsu7cx9KjC vps-autopilot";

    @Test
    void buildsIdempotentAuthorizedKeysScript() {
        String script = SshKeyInstallScript.installAuthorizedKey(KEY);
        assertThat(script).contains("mkdir -p \"$HOME/.ssh\"");
        assertThat(script).contains("authorized_keys");
        assertThat(script).contains(KEY);
        assertThat(script).contains("KEY_ALREADY_PRESENT");
        assertThat(script).contains("KEY_INSTALLED");
        assertThat(script).doesNotContain("rm ");
    }

    @Test
    void rejectsUnsafeKeyMaterial() {
        assertThatThrownBy(() -> SshKeyInstallScript.installAuthorizedKey("ssh-ed25519 AAAA'injection"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshKeyInstallScript.installAuthorizedKey("not-a-key"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
