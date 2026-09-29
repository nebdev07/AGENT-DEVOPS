package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitDeployKeyStoreTest {

    @TempDir
    Path tmp;

    @Test
    void generatesEd25519KeyPair() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(tmp.toString());
        GitDeployKeyStore store = new GitDeployKeyStore(props);
        assertThat(store.present()).isFalse();
        store.generate(false);
        assertThat(store.present()).isTrue();
        assertThat(store.publicKeyText()).startsWith("ssh-ed25519 ");
        assertThat(store.fingerprint()).contains("SHA256:");
        assertThat(store.privateKeyBase64()).isNotBlank();
    }
}
