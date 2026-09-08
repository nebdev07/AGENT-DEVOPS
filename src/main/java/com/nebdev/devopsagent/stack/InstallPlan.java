package com.nebdev.devopsagent.stack;

import java.util.List;

public record InstallPlan(String host, String source, List<InstallAction> actions) {

    public enum Status {
        READY,
        SKIP,
        PLANNED,
        REFUSED
    }

    public record InstallAction(
            String id,
            String label,
            String phase,
            Status status,
            String risk,
            String reason,
            List<String> steps
    ) {}
}
