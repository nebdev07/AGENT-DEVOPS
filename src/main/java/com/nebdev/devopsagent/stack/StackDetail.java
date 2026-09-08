package com.nebdev.devopsagent.stack;

public record StackDetail(
        String id,
        String label,
        String why,
        String what,
        boolean defaultEnabled,
        StackComponent.PolicyImpact offImpact,
        String offWarning,
        boolean present
) {
    public StackDetail(
            String id,
            String label,
            String why,
            String what,
            boolean defaultEnabled,
            StackComponent.PolicyImpact offImpact,
            String offWarning
    ) {
        this(id, label, why, what, defaultEnabled, offImpact, offWarning, false);
    }

    public StackDetail withPresence(boolean alreadyThere) {
        return new StackDetail(
                id, label, why, what,
                defaultEnabled && !alreadyThere,
                offImpact,
                offWarning,
                alreadyThere
        );
    }
}
