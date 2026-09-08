package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.config.DevopsProperties;
import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.discovery.DiscoveryService.DiscoveryResult;
import com.nebdev.devopsagent.discovery.model.LiveSnapshot;
import com.nebdev.devopsagent.discovery.model.VpsParameters;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.HostKeyService;
import com.nebdev.devopsagent.ssh.SshBootstrapService;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.state.StateStore;
import com.nebdev.devopsagent.state.StateStore.InfrastructureState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DiscoverController {

    private final DiscoveryService discoveryService;
    private final DevopsProperties properties;
    private final StateStore stateStore;
    private final HostKeyService hostKeyService;
    private final SshBootstrapService sshBootstrapService;

    public DiscoverController(
            DiscoveryService discoveryService,
            DevopsProperties properties,
            StateStore stateStore,
            HostKeyService hostKeyService,
            SshBootstrapService sshBootstrapService
    ) {
        this.discoveryService = discoveryService;
        this.properties = properties;
        this.stateStore = stateStore;
        this.hostKeyService = hostKeyService;
        this.sshBootstrapService = sshBootstrapService;
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() throws IOException {
        boolean ssh = properties.isSshConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("product", "vps-autopilot");
        body.put("phase", "V0-discovery");
        body.put("mode", ssh ? "ssh" : "awaiting-target");
        body.put("sshConfigured", ssh);
        body.put("keyConfigured", properties.hasPrivateKey());
        body.put("host", properties.getSsh().getHost() == null ? "" : properties.getSsh().getHost());
        body.put("user", properties.getSsh().getUser() == null ? "" : properties.getSsh().getUser());
        body.put("port", properties.getSsh().getPort());
        body.put("fixture", properties.getLabFixture());
        body.put("publicKey", readPublicKey());
        body.put("osChoices", List.of(
                "auto", "ubuntu", "debian", "rhel", "rocky", "alma", "fedora", "alpine", "amazon"
        ));
        return body;
    }

    @GetMapping("/state")
    public ResponseEntity<InfrastructureState> latest() throws IOException {
        InfrastructureState state = stateStore.loadLatest();
        if (state == null || !LiveSnapshot.isLive(state.lastDiscovery())) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(state);
    }

    @PostMapping("/ssh/accept-host")
    public Map<String, String> acceptHost(@RequestBody DiscoverRequest body) throws IOException, InterruptedException {
        String host = requiredHost(body);
        int port = body == null || body.port() == null ? 22 : body.port();
        var accepted = hostKeyService.accept(host, port);
        return Map.of(
                "status", "ok",
                "host", accepted.host(),
                "port", String.valueOf(accepted.port()),
                "fingerprint", accepted.fingerprint()
        );
    }

    @PostMapping("/ssh/install-key")
    public Map<String, String> installKey(@RequestBody InstallKeyRequest body) throws IOException, InterruptedException {
        if (body == null || body.host() == null || body.host().isBlank()) {
            throw new IllegalArgumentException("Indiquez l'IP ou le nom d'hôte du VPS.");
        }
        String host = body.host().trim();
        int port = body.port() == null ? 22 : body.port();
        String user = body.user() == null ? "" : body.user().trim();
        hostKeyService.accept(host, port);
        var result = sshBootstrapService.installAgentPublicKey(
                new SshTarget(host, port, user),
                body.password(),
                Boolean.TRUE.equals(body.confirm())
        );
        return Map.of(
                "status", "ok",
                "outcome", result.outcome(),
                "host", result.target().host(),
                "user", result.target().user(),
                "risk", "CHANGE"
        );
    }

    @PostMapping("/discover")
    public Map<String, Object> discover(@RequestBody(required = false) DiscoverRequest body) throws IOException {
        String fixture = body == null ? null : body.fixture();
        DiscoveryResult result;
        String mode;
        if (fixture != null && !fixture.isBlank()) {
            result = discoveryService.discoverFixture(fixture);
            mode = "fixture";
        } else if (body != null && body.host() != null && !body.host().isBlank()) {
            VpsParameters params = VpsParameters.from(body.name(), body.host(), body.port(), body.user(), body.os());
            if (params.user().isBlank()) {
                throw new IllegalArgumentException("Indiquez l'utilisateur SSH (paramètre).");
            }
            result = discoveryService.discoverRemote(
                    new SshTarget(params.host(), params.port(), params.user()), params);
            mode = "ssh";
        } else if (properties.isSshConfigured()) {
            result = discoveryService.discoverRemote();
            mode = "ssh";
        } else {
            throw new IllegalArgumentException(
                    "Indiquez l’IP, l’utilisateur SSH, puis lancez l’analyse. Aucune fixture n’est affichée.");
        }
        return Map.of(
                "mode", mode,
                "snapshot", result.snapshot(),
                "report", result.report(),
                "stateFile", result.stateFile().toString()
        );
    }

    @ExceptionHandler(UnsafeActionException.class)
    public ResponseEntity<Map<String, String>> forbidden(UnsafeActionException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({IOException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> remote(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    private static String requiredHost(DiscoverRequest body) {
        if (body == null || body.host() == null || body.host().isBlank()) {
            throw new IllegalArgumentException("Indiquez l'IP ou le nom d'hôte du VPS.");
        }
        return body.host().trim();
    }

    private String readPublicKey() throws IOException {
        String key = properties.getSsh().getPrivateKeyPath();
        if (key == null || key.isBlank()) {
            return "";
        }
        Path pub = Path.of(key + ".pub");
        if (!Files.isRegularFile(pub)) {
            return "";
        }
        return Files.readString(pub).strip();
    }

    public record DiscoverRequest(
            String fixture,
            Boolean remote,
            String name,
            String host,
            Integer port,
            String user,
            String os
    ) {}

    public record InstallKeyRequest(
            String host,
            Integer port,
            String user,
            String password,
            Boolean confirm
    ) {}
}
