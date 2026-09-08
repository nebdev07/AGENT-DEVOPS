package com.nebdev.devopsagent.discovery;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Script READ-only, injecté via stdin ({@code bash -s}). Rien n'est écrit sur le VPS.
 */
public final class DiscoveryScript {

    public static final String RESOURCE = "scripts/discovery.sh";

    private DiscoveryScript() {}

    public static String load() {
        try {
            byte[] bytes = new ClassPathResource(RESOURCE).getInputStream().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger " + RESOURCE, e);
        }
    }
}
