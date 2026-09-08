package com.nebdev.devopsagent.ssh;

import com.nebdev.devopsagent.config.DevopsProperties;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshBootstrapServiceTest {

    @TempDir
    Path tmp;

    @Test
    void blockedHostRejectedEvenWhenConfirmed() throws Exception {
        Path key = tmp.resolve("id");
        Files.writeString(key, "private");
        Files.writeString(Path.of(key + ".pub"),
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIL8p88ZMRkkFRXsBmwLMlie6HN4X1tG3G7Jsu7cx9KjC test");
        DevopsProperties props = new DevopsProperties();
        props.getSsh().setPrivateKeyPath(key.toString());
        SshBootstrapService svc = new SshBootstrapService(
                new ActionGuard(),
                new ForbiddenTargetGuard(),
                null,
                props
        );
        assertThatThrownBy(() -> svc.installAgentPublicKey(
                new SshTarget("54.37.156.11", 22, "ubuntu"),
                "secret",
                true
        )).isInstanceOf(UnsafeActionException.class)
                .hasMessageContaining("autorisée");
    }
}
