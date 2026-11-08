package com.nebdev.devopsagent.ssh;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Suivi des opérations longues côté agent (déploiements) : la requête HTTP rend un {@code jobId}
 * immédiatement ; le navigateur interroge l’état sans garder une connexion ouverte 20 minutes
 * (évite le faux « Failed to fetch »).
 * <p>
 * Prend aussi le {@link DeployLock} hôte pour refuser un second déploiement concurrent.
 */
@Component
public class LongJobTracker {

    public enum State {
        RUNNING, SUCCEEDED, FAILED
    }

    @FunctionalInterface
    public interface JobWork {
        Map<String, Object> run() throws Exception;
    }

    public record Job(
            String id,
            String kind,
            String label,
            String host,
            State state,
            Instant startedAt,
            Instant finishedAt,
            String error,
            Map<String, Object> result
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jobId", id);
            out.put("kind", kind);
            out.put("label", label);
            out.put("host", host == null ? "" : host);
            out.put("state", state.name());
            out.put("startedAt", startedAt.toString());
            out.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            out.put("error", error == null ? "" : error);
            out.put("result", result == null ? Map.of() : result);
            out.put("running", state == State.RUNNING);
            out.put("ok", state == State.SUCCEEDED);
            return out;
        }
    }

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> progressById = new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentJobId = new ThreadLocal<>();
    private final DeployLock deployLock;
    private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "vpsap-long-job-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    public LongJobTracker(DeployLock deployLock) {
        this.deployLock = deployLock;
    }

    /** Progression distante (JOB_TAIL) pour le poll navigateur — thread du job uniquement. */
    public void reportProgress(String line) {
        String id = currentJobId.get();
        if (id == null || line == null || line.isBlank()) {
            return;
        }
        progressById.put(id, line.strip());
    }

    public String start(String kind, String label, String host, JobWork work) {
        String id = Instant.now().toString().replace(":", "").replace(".", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Optional<DeployLock.Holder> busy = deployLock.tryAcquire(host, id, kind, label);
        if (busy.isPresent()) {
            throw new DeployBusyException(busy.get());
        }
        Job running = new Job(id, kind, label, host, State.RUNNING, Instant.now(), null, null, null);
        jobs.put(id, running);
        executor.submit(() -> {
            currentJobId.set(id);
            try {
                Map<String, Object> result = work.run();
                jobs.put(id, new Job(id, kind, label, host, State.SUCCEEDED, running.startedAt(),
                        Instant.now(), null, result == null ? Map.of() : Map.copyOf(result)));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                // Cause / Où / À faire Maven peut dépasser 800 (type + fichier + consignes).
                if (msg.length() > 2000) {
                    msg = msg.substring(0, 2000);
                }
                jobs.put(id, new Job(id, kind, label, host, State.FAILED, running.startedAt(),
                        Instant.now(), msg, Map.of()));
            } finally {
                currentJobId.remove();
                deployLock.release(id);
            }
        });
        return id;
    }

    public Optional<Job> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jobs.get(id.strip()));
    }

    public Job require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException(
                "Tâche inconnue ou expirée (redémarrage de l’agent). Relance l’opération."));
    }

    public Map<String, Object> snapshot(String id) {
        Job job = require(id);
        Map<String, Object> out = job.toMap();
        out.put("progress", progressById.getOrDefault(job.id(), ""));
        return out;
    }
}
