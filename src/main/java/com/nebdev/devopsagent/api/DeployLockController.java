package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.ssh.DeployLock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/deploy")
public class DeployLockController {

    private final DeployLock deployLock;

    public DeployLockController(DeployLock deployLock) {
        this.deployLock = deployLock;
    }

    /** État du verrou (bandeau cockpit) — un hôte ou tous. */
    @GetMapping("/active")
    public Map<String, Object> active(@RequestParam(value = "host", required = false) String host) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (host != null && !host.isBlank()) {
            var cur = deployLock.current(host);
            out.put("busy", cur.isPresent());
            out.put("active", cur.map(DeployLock.Holder::toMap).orElse(null));
            out.put("host", DeployLock.normalizeHost(host));
            return out;
        }
        out.put("busy", !deployLock.all().isEmpty());
        out.put("active", deployLock.all().stream().map(DeployLock.Holder::toMap).toList());
        return out;
    }
}
