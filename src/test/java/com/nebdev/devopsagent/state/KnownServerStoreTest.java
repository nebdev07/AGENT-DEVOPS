package com.nebdev.devopsagent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnownServerStoreTest {

    @TempDir
    Path temp;

    private KnownServerStore store() {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(temp.toString());
        return new KnownServerStore(props, new ObjectMapper());
    }

    @Test
    void remembersMostRecentFirstAndUpdatesInPlace() throws Exception {
        KnownServerStore store = store();
        store.remember("labo", "198.51.100.7", 22, "autopilot", "auto", Instant.parse("2026-10-01T10:00:00Z"));
        store.remember("dev", "192.0.2.44", 90, "agentweb", "ubuntu", Instant.parse("2026-10-02T10:00:00Z"));
        store.remember("labo-2", "198.51.100.7", 22, "deploy", "auto", Instant.parse("2026-10-03T10:00:00Z"));

        var all = store().list();
        assertThat(all).extracting(KnownServerStore.KnownServer::host).containsExactly("198.51.100.7", "192.0.2.44");
        assertThat(all.get(0).user()).isEqualTo("deploy");
        assertThat(all.get(0).name()).isEqualTo("labo-2");
        assertThat(all.get(1).port()).isEqualTo(90);
    }

    @Test
    void forgetRemovesOnlyTheMatchingPort() throws Exception {
        KnownServerStore store = store();
        store.remember("", "198.51.100.7", 22, "autopilot", "auto", Instant.now());
        store.remember("", "198.51.100.7", 2222, "autopilot", "auto", Instant.now());

        assertThat(store.forget("198.51.100.7", 22)).isTrue();
        assertThat(store.forget("198.51.100.7", 22)).isFalse();
        assertThat(store.list()).extracting(KnownServerStore.KnownServer::port).containsExactly(2222);
    }

    @Test
    void neverStoresSecretsAndRefusesIncompleteEntries() throws Exception {
        KnownServerStore store = store();
        store.remember("labo", "198.51.100.7", 22, "autopilot", "auto", Instant.now());
        String json = Files.readString(temp.resolve(KnownServerStore.FILE_NAME));
        assertThat(json).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("key");

        assertThatThrownBy(() -> store.remember("x", " ", 22, "autopilot", "auto", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knownServersFileIsNotTakenForASnapshot() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(temp.toString());
        store().remember("labo", "198.51.100.7", 22, "autopilot", "auto", Instant.now());
        assertThat(new StateStore(props, new ObjectMapper()).loadLatest()).isNull();
    }
}
