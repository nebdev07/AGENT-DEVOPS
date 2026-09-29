package com.nebdev.devopsagent.stack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformIntentStoreTest {

    @TempDir
    Path tmp;

    @Test
    void roundTripAndRejectsHostClashWithApp() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(tmp.toString());
        ObjectMapper mapper = new ObjectMapper();
        AppIntentStore apps = new AppIntentStore(props, mapper);
        apps.save(List.of(AppSpec.normalized(
                "my-bourse",
                "https://github.com/org/bourse.git",
                "main",
                "www.clash.apps.exemple.com",
                3000
        )));
        PlatformIntentStore store = new PlatformIntentStore(props, mapper, apps);
        PlatformSpec platform = PlatformSpec.normalized(
                "ma-asso",
                "apps.exemple.com",
                "host",
                List.of(new PlatformServiceSpec(
                        "www",
                        "https://github.com/org/site.git",
                        "main",
                        "www.clash.apps.exemple.com",
                        3000,
                        "",
                        List.of(),
                        Map.of(),
                        ""
                )),
                null
        );
        assertThatThrownBy(() -> store.save(List.of(platform), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("déjà pris");
    }

    @Test
    void savesThenLoads() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(tmp.toString());
        ObjectMapper mapper = new ObjectMapper();
        PlatformIntentStore store = new PlatformIntentStore(props, mapper, new AppIntentStore(props, mapper));
        PlatformSpec platform = PlatformSpec.normalized(
                "ma-asso",
                "apps.exemple.com",
                "host",
                List.of(new PlatformServiceSpec(
                        "api",
                        "https://github.com/org/api.git",
                        "main",
                        "",
                        8080,
                        "backend/api",
                        List.of("mariadb", "volume:uploads"),
                        Map.of(),
                        ""
                )),
                null
        );
        List<PlatformSpec> saved = store.save(List.of(platform), null);
        assertThat(saved).hasSize(1);
        assertThat(store.load().get(0).services().get(0).context()).isEqualTo("backend/api");
        assertThat(store.delete("ma-asso")).isEmpty();
    }
}
