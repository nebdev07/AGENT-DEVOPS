package com.nebdev.devopsagent.ssh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedHostStoreTest {

    @TempDir
    Path temp;

    @Test
    void trustsFingerprintForHost() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(temp.toString());
        TrustedHostStore store = new TrustedHostStore(props, new ObjectMapper());
        store.trust("57.128.186.38", 22, "A3:21:9D:8B:22:5A:EA:C9:58:06:4E:35:BD:E3:C9:E1");
        assertThat(store.isTrusted("57.128.186.38", 22, "a3:21:9d:8b:22:5a:ea:c9:58:06:4e:35:bd:e3:c9:e1")).isTrue();
        assertThat(store.isTrusted("57.128.186.38", 22, "ff:ff")).isFalse();
    }
}
