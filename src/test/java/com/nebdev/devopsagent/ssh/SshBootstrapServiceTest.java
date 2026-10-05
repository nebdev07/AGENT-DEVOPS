package com.nebdev.devopsagent.ssh;

import com.nebdev.devopsagent.config.DevopsProperties;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                new ForbiddenTargetGuard(List.of("198.51.100.7"), List.of()),
                null,
                props
        );
        assertThatThrownBy(() -> svc.installAgentPublicKey(
                new SshTarget("198.51.100.7", 22, "autopilot"),
                "secret",
                true
        )).isInstanceOf(UnsafeActionException.class)
                .hasMessageContaining("autorisée");
    }
}
