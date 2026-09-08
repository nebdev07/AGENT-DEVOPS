package com.nebdev.devopsagent.discovery;

import com.nebdev.devopsagent.config.DevopsProperties;
import com.nebdev.devopsagent.report.DiscoveryReportFormatter;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.state.StateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryServiceFixtureTest {

    @TempDir
    Path temp;

    @Test
    void fixtureDiscoveryWritesStateWithoutSsh() throws Exception {
        DevopsProperties props = new DevopsProperties();
        props.setStateDir(temp.toString());
        StateStore store = new StateStore(props, new ObjectMapper());
        DiscoveryService service = new DiscoveryService(
                new ActionGuard(),
                new ForbiddenTargetGuard(),
                null,
                new DiscoveryParser(),
                store,
                new DiscoveryReportFormatter(),
                props
        );

        var result = service.discoverFixture("classpath:fixtures/ubuntu-vps2-blank.txt");

        assertThat(result.snapshot().osId()).isEqualTo("ubuntu");
        assertThat(result.report()).contains("READ only");
        assertThat(result.report()).contains("Docker");
        assertThat(result.stateFile()).exists();
        String json = java.nio.file.Files.readString(result.stateFile());
        assertThat(json).contains("\"docker\" : false");
        assertThat(json).contains("\"schema\" : \"v0\"");
    }
}
