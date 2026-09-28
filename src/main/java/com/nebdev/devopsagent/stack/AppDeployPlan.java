package com.nebdev.devopsagent.stack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plan CHANGE avant déploiement : actions listées pour le popup de confirmation (ADR-032).
 */
public record AppDeployPlan(
        String id,
        List<String> actions,
        List<String> warnings,
        boolean canProceed,
        String blocker,
        Scaffold scaffold
) {
    public AppDeployPlan {
        actions = actions == null ? List.of() : List.copyOf(actions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public record Scaffold(String kind, int port, String dockerfileBase64, String summary) {
        public boolean present() {
            return dockerfileBase64 != null && !dockerfileBase64.isBlank();
        }
    }

    public Optional<Scaffold> scaffoldOpt() {
        return scaffold != null && scaffold.present() ? Optional.of(scaffold) : Optional.empty();
    }

    public static AppDeployPlan blocked(String id, List<String> actions, String blocker) {
        return new AppDeployPlan(id, actions, List.of(), false, blocker, null);
    }

    public static AppDeployPlan ready(String id, List<String> actions, List<String> warnings, Scaffold scaffold) {
        return new AppDeployPlan(id, actions, warnings, true, null, scaffold);
    }

    public List<String> actionsMutable() {
        return new ArrayList<>(actions);
    }
}
