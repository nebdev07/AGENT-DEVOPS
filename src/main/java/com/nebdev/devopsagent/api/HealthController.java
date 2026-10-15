package com.nebdev.devopsagent.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

@RestController
public class HealthController {

    private final Environment environment;
    private final String version;

    public HealthController(Environment environment, ObjectProvider<BuildProperties> build) {
        this.environment = environment;
        BuildProperties props = build.getIfAvailable();
        this.version = props == null ? "dev" : props.getVersion();
    }

    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        boolean cargo = Arrays.asList(environment.getActiveProfiles()).contains("cargo");
        return Map.of(
                "status", "UP",
                "product", "vps-autopilot",
                "version", version,
                "phase", "V0-discovery",
                "role", cargo ? "cargo" : "operator"
        );
    }
}
