package com.nebdev.devopsagent.api;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

@RestController
public class HealthController {

    private final Environment environment;

    public HealthController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        boolean cargo = Arrays.asList(environment.getActiveProfiles()).contains("cargo");
        return Map.of(
                "status", "UP",
                "product", "vps-autopilot",
                "phase", "V0-discovery",
                "role", cargo ? "cargo" : "operator"
        );
    }
}
