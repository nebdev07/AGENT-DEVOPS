package com.nebdev.devopsagent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevopsPropertiesTest {

    @Test
    void sshConfiguredRequiresHostUserAndKey() {
        DevopsProperties p = new DevopsProperties();
        assertThat(p.isSshConfigured()).isFalse();
        p.getSsh().setHost("127.0.0.1");
        p.getSsh().setUser("lab");
        assertThat(p.isSshConfigured()).isFalse();
        p.getSsh().setPrivateKeyPath("infrastructure/lab/keys/lab");
        assertThat(p.isSshConfigured()).isTrue();
    }
}
