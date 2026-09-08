package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StackIntentStoreTest {

    @TempDir
    Path temp;

    @Test
    void oldIntentWithoutDeployGainsDefaultEnabledDeployOnce() {
        var old = new StackIntentStore.Intent(
                List.of("security", "docker", "traefik", "mariadb", "backups"),
                Map.of(
                        "security", List.of("ssh-harden"),
                        "docker", List.of("compose"),
                        "traefik", List.of("proxy"),
                        "mariadb", List.of("instance"),
                        "backups", List.of("encrypted-dump")
                )
        );
        var merged = StackIntentStore.mergeNewDefaults(old);
        assertThat(merged.enabled()).contains("deploy");
        assertThat(merged.details()).containsKey("deploy");
        assertThat(merged.catalogSeen()).contains("deploy", "redis");
    }

    @Test
    void uncheckingDeployIsNotRevertedOnNextMerge() {
        var afterOptOut = new StackIntentStore.Intent(
                List.of("security", "docker", "traefik", "mariadb", "backups"),
                Map.of("security", List.of("ssh-harden")),
                List.of("security", "docker", "traefik", "mariadb", "deploy", "redis", "backups")
        );
        var merged = StackIntentStore.mergeNewDefaults(afterOptOut);
        assertThat(merged.enabled()).doesNotContain("deploy");
    }

    @Test
    void loadPersistsMigratedDeploy() throws Exception {
        Path file = temp.resolve("stack-intent.json");
        Files.writeString(file, """
                {
                  "enabled" : [ "security", "docker", "traefik", "mariadb", "backups" ],
                  "details" : {
                    "security" : [ "ssh-harden" ],
                    "docker" : [ "compose" ],
                    "traefik" : [ "proxy" ],
                    "mariadb" : [ "instance" ],
                    "backups" : [ "encrypted-dump" ]
                  }
                }
                """);
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(temp.toString());
        StackIntentStore store = new StackIntentStore(props, new ObjectMapper());
        assertThat(store.load()).contains("deploy");
        StackIntentStore.Intent onDisk = new ObjectMapper().readValue(file.toFile(), StackIntentStore.Intent.class);
        assertThat(onDisk.enabled()).contains("deploy");
        assertThat(onDisk.catalogSeen()).contains("deploy");
    }

    @Test
    void redisStaysOffWhenUnknownToOldIntent() {
        var old = new StackIntentStore.Intent(
                List.of("security", "docker"),
                Map.of()
        );
        var merged = StackIntentStore.mergeNewDefaults(old);
        assertThat(merged.enabled()).doesNotContain("redis");
        assertThat(merged.enabled()).contains("deploy");
    }

    @Test
    void saveKeepsCatalogSeenSoOptOutSticks() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(temp.toString());
        StackIntentStore store = new StackIntentStore(props, new ObjectMapper());
        store.save(Set.of("security", "docker", "traefik", "mariadb", "backups"), Map.of());
        store.save(Set.of("security", "docker", "traefik", "mariadb", "backups"), Map.of());
        assertThat(store.load()).doesNotContain("deploy");
        StackIntentStore.Intent onDisk = new ObjectMapper()
                .readValue(temp.resolve("stack-intent.json").toFile(), StackIntentStore.Intent.class);
        assertThat(onDisk.catalogSeen()).contains("deploy");
    }
}
