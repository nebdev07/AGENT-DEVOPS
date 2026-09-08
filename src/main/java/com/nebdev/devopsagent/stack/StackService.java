package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.discovery.model.ServerSnapshot;
import com.nebdev.devopsagent.state.StateStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StackService {

    private final StackIntentStore store;
    private final StateStore stateStore;

    public StackService(StackIntentStore store, StateStore stateStore) {
        this.store = store;
        this.stateStore = stateStore;
    }

    public StackView current() throws IOException {
        StackIntentStore.Intent intent = store.loadIntent();
        return view(new LinkedHashSet<>(intent.enabled()), intent.details());
    }

    public StackView apply(Set<String> requested, boolean acknowledgeWarnings) throws IOException {
        return apply(requested, null, acknowledgeWarnings);
    }

    public StackView apply(
            Set<String> requested,
            Map<String, List<String>> details,
            boolean acknowledgeWarnings
    ) throws IOException {
        Map<String, List<String>> present = presentFromState();
        StackPolicy.Evaluation eval = StackPolicy.evaluate(requested, details, present);
        if (eval.policyBreak() && !acknowledgeWarnings) {
            throw new StackPolicyException(eval);
        }
        store.save(new LinkedHashSet<>(eval.enabled()), eval.details());
        return view(new LinkedHashSet<>(eval.enabled()), eval.details());
    }

    private StackView view(Set<String> enabled, Map<String, List<String>> details) throws IOException {
        Map<String, List<String>> present = presentFromState();
        StackPolicy.Evaluation eval = StackPolicy.evaluate(enabled, details, present);
        return new StackView(
                catalogWithPresence(present),
                StackCatalog.notOffered(),
                eval.enabled(),
                eval.details(),
                present,
                eval.warnings(),
                eval.policyBreak()
        );
    }

    static java.util.List<StackComponent> catalogWithPresence(Map<String, List<String>> present) {
        Map<String, List<String>> already = present == null ? Map.of() : present;
        return StackCatalog.all().stream()
                .map(c -> {
                    java.util.Set<String> here = new java.util.LinkedHashSet<>(already.getOrDefault(c.id(), List.of()));
                    return new StackComponent(
                            c.id(),
                            c.label(),
                            c.phase(),
                            c.summary(),
                            c.why(),
                            c.what(),
                            c.defaultEnabled(),
                            c.discouragedWhenOn(),
                            c.offImpact(),
                            c.offWarning(),
                            c.onWarning(),
                            c.requires(),
                            c.details().stream().map(d -> d.withPresence(here.contains(d.id()))).toList()
                    );
                })
                .toList();
    }

    private Map<String, List<String>> presentFromState() throws IOException {
        StateStore.InfrastructureState state = stateStore.loadLatest();
        ServerSnapshot snap = state == null ? null : state.lastDiscovery();
        return StackPresence.of(snap);
    }

    public record StackView(
            java.util.List<StackComponent> catalog,
            java.util.List<StackCatalog.RefusedOffer> notOffered,
            java.util.List<String> enabled,
            Map<String, List<String>> details,
            Map<String, List<String>> presentDetails,
            java.util.List<StackPolicy.Warning> warnings,
            boolean policyBreak
    ) {}
}
