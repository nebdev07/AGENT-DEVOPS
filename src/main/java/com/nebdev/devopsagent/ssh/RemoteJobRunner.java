package com.nebdev.devopsagent.ssh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exécute un script de rôle détaché de la session SSH (ADR-040) : dépôt dans un dossier de tâche,
 * lancement par {@code systemd-run} (repli {@code setsid nohup}), suivi par de courtes connexions.
 * Une coupure SSH pendant un build long ne fait plus échouer l'opération.
 * Même contrat que {@link SshConnector#runStdinScript(String, SshTarget, int)} : sortie standard en retour,
 * {@link IOException} « Commande distante exit=… ###STDOUT### … ###STDERR### … » si le script échoue.
 */
@Component
public class RemoteJobRunner {

    private static final Logger log = LoggerFactory.getLogger(RemoteJobRunner.class);
    private static final Pattern JOB_ID = Pattern.compile("^\\d{8}-\\d{6}-[a-f0-9]{8}$");
    private static final Pattern EXIT = Pattern.compile("(?m)^JOB_EXIT=(\\d+)\\s*$");
    private static final Pattern TAIL = Pattern.compile("(?m)^JOB_TAIL=(.*)$");
    private static final int SHORT_TIMEOUT_SECONDS = 90;
    private static final int LAUNCH_ATTEMPTS = 3;
    /** Sans processus ni code de sortie au-delà de ce délai, la tâche est considérée perdue. */
    private static final long STARTING_GRACE_MILLIS = 120_000;

    @FunctionalInterface
    public interface RemoteShell {
        String run(String script, SshTarget target, int timeoutSeconds) throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final RemoteShell shell;
    private final long pollMillis;
    private final Sleeper sleeper;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public RemoteJobRunner(SshConnector ssh) {
        this(ssh::runStdinScript, 10_000, Thread::sleep, System::currentTimeMillis);
    }

    RemoteJobRunner(RemoteShell shell, long pollMillis, Sleeper sleeper, LongSupplier clock) {
        this.shell = shell;
        this.pollMillis = pollMillis;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public String run(String script, SshTarget target, int timeoutSeconds) throws IOException {
        String jobId = newJobId();
        launch(jobId, script, target);
        long deadline = clock.getAsLong() + timeoutSeconds * 1000L;
        long startedAt = clock.getAsLong();
        IOException lastFailure = null;
        while (clock.getAsLong() < deadline) {
            String state;
            try {
                state = shell.run(withJob(jobId, load("scripts/job-poll.sh")), target, SHORT_TIMEOUT_SECONDS);
                lastFailure = null;
            } catch (IOException e) {
                lastFailure = e;
                log.warn("Tâche {} sur {} : suivi interrompu ({}), nouvel essai.", jobId, target.host(), e.getMessage());
                pause();
                continue;
            }
            Matcher exit = EXIT.matcher(state);
            if (exit.find()) {
                return collect(jobId, target, Integer.parseInt(exit.group(1)));
            }
            if (state.contains("JOB_UNKNOWN") || state.contains("JOB_LOST")) {
                throw new IOException("Tâche " + jobId + " interrompue sur le serveur sans code de sortie "
                        + "(redémarrage du serveur ou processus tué). Journal : ~/.vps-autopilot/jobs/" + jobId);
            }
            if (state.contains("JOB_STARTING") && clock.getAsLong() - startedAt > STARTING_GRACE_MILLIS) {
                throw new IOException("Tâche " + jobId + " jamais démarrée sur le serveur.");
            }
            Matcher tail = TAIL.matcher(state);
            if (tail.find() && !tail.group(1).isBlank()) {
                log.info("Tâche {} : {}", jobId, tail.group(1).strip());
            }
            pause();
        }
        String reason = lastFailure == null ? "" : " Dernière erreur SSH : " + lastFailure.getMessage();
        throw new IOException("Tâche " + jobId + " toujours en cours après " + timeoutSeconds
                + " s. Elle continue sur le serveur (journal ~/.vps-autopilot/jobs/" + jobId + "/out.log)." + reason);
    }

    private void launch(String jobId, String script, SshTarget target) throws IOException {
        String b64 = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String launcher = "JOB_SCRIPT_B64='" + b64 + "'\n" + withJob(jobId, load("scripts/job-launch.sh"));
        IOException last = null;
        for (int attempt = 1; attempt <= LAUNCH_ATTEMPTS; attempt++) {
            try {
                String out = shell.run(launcher, target, SHORT_TIMEOUT_SECONDS);
                if (out.contains("JOB_STARTED=" + jobId)) {
                    log.info("Tâche {} lancée sur {} ({}).", jobId, target.host(),
                            out.contains("JOB_MODE=systemd") ? "systemd" : "nohup");
                    return;
                }
                throw new IOException("Lancement de la tâche sans accusé : " + out.strip());
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("Commande distante exit=")) {
                    throw e;
                }
                last = e;
                log.warn("Tâche {} : lancement interrompu ({}), essai {}/{}.", jobId, e.getMessage(), attempt, LAUNCH_ATTEMPTS);
                pause();
            }
        }
        throw last;
    }

    private String collect(String jobId, SshTarget target, int exitCode) throws IOException {
        String raw = null;
        IOException last = null;
        for (int attempt = 1; attempt <= LAUNCH_ATTEMPTS && raw == null; attempt++) {
            try {
                raw = shell.run(withJob(jobId, load("scripts/job-fetch.sh")), target, SHORT_TIMEOUT_SECONDS);
            } catch (IOException e) {
                last = e;
                pause();
            }
        }
        if (raw == null) {
            throw last;
        }
        String stdout = decodeBetween(raw, "###JOB_OUT###", "###JOB_ERR###");
        String stderr = decodeBetween(raw, "###JOB_ERR###", "###JOB_END###");
        if (exitCode != 0) {
            throw new IOException("Commande distante exit=" + exitCode
                    + "\n###STDOUT###\n" + stdout.strip()
                    + "\n###STDERR###\n" + stderr.strip());
        }
        return stderr.isBlank() ? stdout : stdout + "\n###WARN###\n" + stderr;
    }

    static String decodeBetween(String raw, String start, String end) {
        int s = raw.indexOf(start);
        int e = raw.indexOf(end);
        if (s < 0 || e < 0 || e < s) {
            throw new IllegalStateException("Sortie de tâche illisible (marqueurs " + start + " / " + end + " absents).");
        }
        String b64 = raw.substring(s + start.length(), e).replaceAll("\\s", "");
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    private String newJobId() {
        byte[] b = new byte[4];
        random.nextBytes(b);
        String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + HexFormat.of().formatHex(b);
        if (!JOB_ID.matcher(id).matches()) {
            throw new IllegalStateException("Identifiant de tâche invalide : " + id);
        }
        return id;
    }

    private static String withJob(String jobId, String body) {
        return "JOB_ID='" + jobId + "'\n" + body;
    }

    private void pause() throws IOException {
        try {
            sleeper.sleep(pollMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Suivi de tâche interrompu.", e);
        }
    }

    static String load(String resource) {
        try {
            byte[] bytes = new ClassPathResource(resource).getInputStream().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger " + resource, e);
        }
    }
}
