package com.nebdev.devopsagent.stack;

import java.util.List;

public record StackComponent(
        String id,
        String label,
        String phase,
        String summary,
        String why,
        String what,
        boolean defaultEnabled,
        boolean discouragedWhenOn,
        PolicyImpact offImpact,
        String offWarning,
        String onWarning,
        List<String> requires,
        List<StackDetail> details
) {
    public enum PolicyImpact {
        NONE,
        WARNING,
        POLICY_BREAK
    }

    public StackComponent {
        requires = requires == null ? List.of() : List.copyOf(requires);
        details = details == null ? List.of() : List.copyOf(details);
    }
}
