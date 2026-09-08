package com.nebdev.devopsagent.stack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class StackPolicy {

    private StackPolicy() {}

    public static Evaluation evaluate(Set<String> requested) {
        return evaluate(requested, null);
    }

    public static Evaluation evaluate(Set<String> requested, Map<String, List<String>> requestedDetails) {
        return evaluate(requested, requestedDetails, Map.of());
    }

    public static Evaluation evaluate(
            Set<String> requested,
            Map<String, List<String>> requestedDetails,
            Map<String, List<String>> presentDetails
    ) {
        Map<String, StackComponent> catalog = StackCatalog.all().stream()
                .collect(Collectors.toMap(StackComponent::id, Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
        Set<String> enabled = new LinkedHashSet<>();
        if (requested != null) {
            for (String id : requested) {
                if (catalog.containsKey(id)) {
                    enabled.add(id);
                }
            }
        }
        List<String> notes = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String id : List.copyOf(enabled)) {
                StackComponent c = catalog.get(id);
                for (String req : c.requires()) {
                    if (!enabled.contains(req)) {
                        enabled.remove(id);
                        notes.add(c.label() + " a été retiré : il exige " + req + ".");
                        changed = true;
                    }
                }
            }
        }
        List<Warning> warnings = new ArrayList<>();
        boolean policyBreak = false;
        for (StackComponent c : catalog.values()) {
            boolean on = enabled.contains(c.id());
            if (!on && c.offImpact() != StackComponent.PolicyImpact.NONE && !c.offWarning().isBlank()) {
                warnings.add(new Warning(c.id(), c.offImpact(), c.offWarning()));
                if (c.offImpact() == StackComponent.PolicyImpact.POLICY_BREAK) {
                    policyBreak = true;
                }
            }
            if (on && c.discouragedWhenOn() && !c.onWarning().isBlank()) {
                warnings.add(new Warning(c.id(), StackComponent.PolicyImpact.POLICY_BREAK, c.onWarning()));
                policyBreak = true;
            }
        }
        warnings.addAll(notes.stream()
                .map(n -> new Warning("docker", StackComponent.PolicyImpact.WARNING, n))
                .toList());
        Map<String, List<String>> present = presentDetails == null ? Map.of() : presentDetails;
        Map<String, List<String>> details = StackPresence.subtract(
                normalizeDetails(enabled, requestedDetails, present),
                present
        );
        for (StackComponent c : catalog.values()) {
            if (!enabled.contains(c.id())) {
                continue;
            }
            Set<String> chosen = new LinkedHashSet<>(details.getOrDefault(c.id(), List.of()));
            Set<String> already = new LinkedHashSet<>(present.getOrDefault(c.id(), List.of()));
            for (StackDetail d : c.details()) {
                if (chosen.contains(d.id()) || already.contains(d.id())) {
                    continue;
                }
                if (d.offImpact() != StackComponent.PolicyImpact.NONE && d.offWarning() != null && !d.offWarning().isBlank()) {
                    warnings.add(new Warning(c.id() + "." + d.id(), d.offImpact(), d.offWarning()));
                    if (d.offImpact() == StackComponent.PolicyImpact.POLICY_BREAK) {
                        policyBreak = true;
                    }
                }
            }
        }
        return new Evaluation(List.copyOf(enabled), details, warnings, policyBreak);
    }

    public static Map<String, List<String>> defaultDetails(Set<String> enabled) {
        return defaultDetails(enabled, Map.of());
    }

    public static Map<String, List<String>> defaultDetails(Set<String> enabled, Map<String, List<String>> present) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        Set<String> on = enabled == null ? Set.of() : enabled;
        Map<String, List<String>> already = present == null ? Map.of() : present;
        for (StackComponent c : StackCatalog.all()) {
            if (!on.contains(c.id())) {
                continue;
            }
            Set<String> skip = new LinkedHashSet<>(already.getOrDefault(c.id(), List.of()));
            out.put(c.id(), c.details().stream()
                    .filter(StackDetail::defaultEnabled)
                    .map(StackDetail::id)
                    .filter(id -> !skip.contains(id))
                    .toList());
        }
        return out;
    }

    public static Map<String, List<String>> normalizeDetails(Set<String> enabled, Map<String, List<String>> requested) {
        return normalizeDetails(enabled, requested, Map.of());
    }

    public static Map<String, List<String>> normalizeDetails(
            Set<String> enabled,
            Map<String, List<String>> requested,
            Map<String, List<String>> present
    ) {
        Map<String, List<String>> defaults = defaultDetails(enabled, present);
        if (requested == null || requested.isEmpty()) {
            return defaults;
        }
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        Map<String, StackComponent> catalog = StackCatalog.all().stream()
                .collect(Collectors.toMap(StackComponent::id, Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
        for (String id : enabled) {
            StackComponent c = catalog.get(id);
            if (c == null) {
                continue;
            }
            Set<String> allowed = c.details().stream().map(StackDetail::id).collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> raw = requested.get(id);
            if (raw == null) {
                out.put(id, defaults.getOrDefault(id, List.of()));
                continue;
            }
            List<String> kept = raw.stream().filter(allowed::contains).toList();
            out.put(id, kept);
        }
        return out;
    }

    public static Set<String> defaults() {
        return StackCatalog.all().stream()
                .filter(StackComponent::defaultEnabled)
                .map(StackComponent::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public record Warning(String componentId, StackComponent.PolicyImpact impact, String message) {}

    public record Evaluation(
            List<String> enabled,
            Map<String, List<String>> details,
            List<Warning> warnings,
            boolean policyBreak
    ) {
        public Evaluation(List<String> enabled, List<Warning> warnings, boolean policyBreak) {
            this(enabled, defaultDetails(new LinkedHashSet<>(enabled)), warnings, policyBreak);
        }
    }
}
