package com.nebdev.devopsagent.ssh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Essai réel sur le VPS lab (jamais la prod). Lancé seulement si AUTOPILOT_LAB_HOST est défini :
 * AUTOPILOT_LAB_HOST, AUTOPILOT_LAB_USER, DEVOPS_SSH_KEY.
 */
@EnabledIfEnvironmentVariable(named = "AUTOPILOT_LAB_HOST", matches = ".+")
class RemoteJobRunnerLabTest {

    private static RemoteJobRunner runner() {
        DevopsProperties props = new DevopsProperties();
        props.getSsh().setPrivateKeyPath(System.getenv("DEVOPS_SSH_KEY"));
        props.getSsh().setTimeoutSeconds(45);
        return new RemoteJobRunner(new SshConnector(props, new TrustedHostStore(props, new ObjectMapper())));
    }

    private static SshTarget lab() {
        String user = System.getenv("AUTOPILOT_LAB_USER");
        return new SshTarget(System.getenv("AUTOPILOT_LAB_HOST"), 22, user == null || user.isBlank() ? "ubuntu" : user);
    }

    @Test
    void jobLongerThanTheObservedSshCutIsCollected() throws IOException {
        String script = "echo START\nsleep 380\necho warn-line >&2\necho LONG_JOB_DONE\n";
        long t0 = System.currentTimeMillis();

        String out = runner().run(script, lab(), 900);

        assertThat(System.currentTimeMillis() - t0).isGreaterThan(370_000);
        assertThat(out).contains("START").contains("LONG_JOB_DONE").contains("###WARN###").contains("warn-line");
    }

    @Test
    void failingJobReturnsExitCodeAndMarkers() {
        assertThatThrownBy(() -> runner().run("echo NEED_DOCKERFILE\nexit 3\n", lab(), 120))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("Commande distante exit=3")
                .hasMessageContaining("NEED_DOCKERFILE");
    }
}
