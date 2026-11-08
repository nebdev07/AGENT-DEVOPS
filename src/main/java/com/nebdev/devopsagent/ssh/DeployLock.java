package com.nebdev.devopsagent.ssh;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Un seul déploiement CHANGE à la fois par hôte VPS.
 * Évite deux {@code docker compose up --build} concurrents qui se marchent dessus
 * (surtout après une coupure SSH alors que le job distant continue).
 */
@Component
public class DeployLock {

    public record Holder(
            String jobId,
            String kind,
            String label,
            String host,
            Instant startedAt
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jobId", jobId);
            out.put("kind", kind);
            out.put("label", label);
            out.put("host", host);
            out.put("startedAt", startedAt.toString());
            out.put("busy", true);
            return out;
        }
    }

    private final ConcurrentHashMap<String, Holder> byHost = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> hostByJob = new ConcurrentHashMap<>();

    public static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        return host.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<Holder> current(String host) {
        String key = normalizeHost(host);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byHost.get(key));
    }

    /**
     * @return empty si le verrou est pris ; sinon le détenteur actuel
     */
    public Optional<Holder> tryAcquire(String host, String jobId, String kind, String label) {
        String key = normalizeHost(host);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Hôte manquant pour le verrou de déploiement.");
        }
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId manquant pour le verrou de déploiement.");
        }
        Holder wanted = new Holder(jobId.strip(), kind == null ? "" : kind, label == null ? "" : label,
                key, Instant.now());
        Holder existing = byHost.putIfAbsent(key, wanted);
        if (existing != null) {
            return Optional.of(existing);
        }
        hostByJob.put(wanted.jobId(), key);
        return Optional.empty();
    }

    public void release(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        String key = hostByJob.remove(jobId.strip());
        if (key == null) {
            return;
        }
        byHost.computeIfPresent(key, (h, holder) ->
                holder.jobId().equals(jobId.strip()) ? null : holder);
    }

    public List<Holder> all() {
        return new ArrayList<>(byHost.values());
    }
}
