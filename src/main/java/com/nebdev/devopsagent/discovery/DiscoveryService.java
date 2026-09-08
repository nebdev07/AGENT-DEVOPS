package com.nebdev.devopsagent.discovery;

import com.nebdev.devopsagent.config.DevopsProperties;
import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import com.nebdev.devopsagent.discovery.model.VpsParameters;
import com.nebdev.devopsagent.report.DiscoveryReportFormatter;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.risk.RiskLevel;
import com.nebdev.devopsagent.ssh.SshConnector;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.state.StateStore;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Service
public class DiscoveryService {

    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final DiscoveryParser parser;
    private final StateStore stateStore;
    private final DiscoveryReportFormatter reportFormatter;
    private final DevopsProperties properties;

    public DiscoveryService(
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            DiscoveryParser parser,
            StateStore stateStore,
            DiscoveryReportFormatter reportFormatter,
            DevopsProperties properties
    ) {
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.parser = parser;
        this.stateStore = stateStore;
        this.reportFormatter = reportFormatter;
        this.properties = properties;
    }

    public DiscoveryResult discoverRemote() throws IOException {
        DevopsProperties.Ssh ssh = properties.getSsh();
        return discoverRemote(new SshTarget(ssh.getHost(), ssh.getPort(), ssh.getUser()));
    }

    public DiscoveryResult discoverRemote(SshTarget target) throws IOException {
        return discoverRemote(target, null);
    }

    public DiscoveryResult discoverRemote(SshTarget target, VpsParameters params) throws IOException {
        actionGuard.assertAllowed(RiskLevel.READ, "discover");
        forbiddenTargetGuard.assertAllowed(target.host());
        String raw = sshConnector.runStdinScript(DiscoveryScript.load(), target);
        return persist(parser.parse(raw, "ssh:" + target.host(), target.host(), params));
    }

    public DiscoveryResult discoverFixture(String location) throws IOException {
        return discoverFixture(location, null);
    }

    public DiscoveryResult discoverFixture(String location, VpsParameters params) throws IOException {
        actionGuard.assertAllowed(RiskLevel.READ, "discover-fixture");
        Resource resource = new DefaultResourceLoader().getResource(location);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Fixture introuvable: " + location);
        }
        String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return persist(parser.parse(raw, "fixture:" + location, "fixture", params));
    }

    private DiscoveryResult persist(ServerSnapshot snapshot) throws IOException {
        Path file = stateStore.save(snapshot);
        return new DiscoveryResult(snapshot, reportFormatter.format(snapshot), file);
    }

    public record DiscoveryResult(ServerSnapshot snapshot, String report, Path stateFile) {}
}
