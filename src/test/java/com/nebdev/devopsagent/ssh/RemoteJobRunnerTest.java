package com.nebdev.devopsagent.ssh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteJobRunnerTest {

    private static final SshTarget TARGET = new SshTarget("203.0.113.10", 22, "agent");

    /** Répond selon le script reçu ; une réponse « DROP » simule une coupure SSH. */
    private static final class FakeShell implements RemoteJobRunner.RemoteShell {
        final Deque<String> launches = new ArrayDeque<>();
        final Deque<String> polls = new ArrayDeque<>();
        final Deque<String> fetches = new ArrayDeque<>();
        final List<String> launchScripts = new ArrayList<>();

        @Override
        public String run(String script, SshTarget target, int timeoutSeconds) throws IOException {
            Deque<String> queue;
            if (script.contains("JOB_SCRIPT_B64=")) {
                launchScripts.add(script);
                queue = launches;
            } else if (script.contains("###JOB_OUT###")) {
                queue = fetches;
            } else {
                queue = polls;
            }
            String next = queue.isEmpty() ? "JOB_RUNNING" : queue.poll();
            if (next.equals("DROP")) {
                throw new IOException("Connection reset");
            }
            return next.replace("{ID}", jobIdOf(launchScripts));
        }

        private static String jobIdOf(List<String> scripts) {
            if (scripts.isEmpty()) {
                return "";
            }
            String s = scripts.get(0);
            int i = s.indexOf("JOB_ID='") + "JOB_ID='".length();
            return s.substring(i, s.indexOf('\'', i));
        }
    }

    private static RemoteJobRunner runner(FakeShell shell, AtomicLong now) {
        return new RemoteJobRunner(shell, 10_000, ms -> now.addAndGet(ms), now::get);
    }

    private static String fetched(String out, String err) {
        return "###JOB_OUT###\n" + b64(out) + "\n###JOB_ERR###\n" + b64(err) + "\n###JOB_END###\n";
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void survivesSshDropsWhileTheJobRuns() throws IOException {
        FakeShell shell = new FakeShell();
        shell.launches.add("DROP");
        shell.launches.add("JOB_STARTED={ID}\nJOB_MODE=systemd\n");
        shell.polls.add("JOB_RUNNING\nJOB_TAIL=Building api\n");
        shell.polls.add("DROP");
        shell.polls.add("DROP");
        shell.polls.add("JOB_EXIT=0\n");
        shell.fetches.add(fetched("CLONED=api@abc\nPLATFORM_DEPLOYED\n", ""));

        String out = runner(shell, new AtomicLong()).run("echo PLATFORM_DEPLOYED", TARGET, 2700);

        assertThat(out).contains("PLATFORM_DEPLOYED").doesNotContain("###WARN###");
        assertThat(shell.launchScripts).hasSize(2);
        assertThat(shell.launchScripts.get(0)).isEqualTo(shell.launchScripts.get(1));
        String sent = shell.launchScripts.get(0);
        String b64 = sent.substring(sent.indexOf("JOB_SCRIPT_B64='") + 16, sent.indexOf("'\n", sent.indexOf("JOB_SCRIPT_B64='")));
        assertThat(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8)).isEqualTo("echo PLATFORM_DEPLOYED");
    }

    @Test
    void failedScriptKeepsTheSshConnectorErrorContract() {
        FakeShell shell = new FakeShell();
        shell.launches.add("JOB_STARTED={ID}\n");
        shell.polls.add("JOB_EXIT=1\n");
        shell.fetches.add(fetched("BUILD_FAILED\nError: boom\n", "Cloning into 'x'..."));

        assertThatThrownBy(() -> runner(shell, new AtomicLong()).run("x", TARGET, 600))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("Commande distante exit=1")
                .hasMessageContaining("###STDOUT###\nBUILD_FAILED")
                .hasMessageContaining("###STDERR###\nCloning into");
    }

    @Test
    void stderrOfASuccessfulJobIsAppendedAsWarning() throws IOException {
        FakeShell shell = new FakeShell();
        shell.launches.add("JOB_STARTED={ID}\n");
        shell.polls.add("JOB_EXIT=0\n");
        shell.fetches.add(fetched("APP_DEPLOYED\n", "warning: x"));

        assertThat(runner(shell, new AtomicLong()).run("x", TARGET, 600))
                .isEqualTo("APP_DEPLOYED\n\n###WARN###\nwarning: x");
    }

    @Test
    void lostJobIsReportedInsteadOfWaitingForever() {
        FakeShell shell = new FakeShell();
        shell.launches.add("JOB_STARTED={ID}\n");
        shell.polls.add("JOB_LOST\n");

        assertThatThrownBy(() -> runner(shell, new AtomicLong()).run("x", TARGET, 600))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("interrompue sur le serveur");
    }

    @Test
    void timeoutSaysTheJobKeepsRunning() {
        FakeShell shell = new FakeShell();
        shell.launches.add("JOB_STARTED={ID}\n");

        assertThatThrownBy(() -> runner(shell, new AtomicLong()).run("x", TARGET, 60))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("toujours en cours")
                .hasMessageContaining("continue sur le serveur");
    }

    @Test
    void remoteLaunchErrorIsNotRetried() {
        FakeShell shell = new FakeShell();
        RemoteJobRunner.RemoteShell failing = (script, target, t) -> {
            if (script.contains("JOB_SCRIPT_B64=")) {
                shell.launchScripts.add(script);
                throw new IOException("Commande distante exit=1\n###STDOUT###\n\n###STDERR###\nbase64: invalid input");
            }
            return "JOB_RUNNING";
        };
        AtomicLong now = new AtomicLong();
        RemoteJobRunner r = new RemoteJobRunner(failing, 10_000, ms -> now.addAndGet(ms), now::get);

        assertThatThrownBy(() -> r.run("x", TARGET, 600)).hasMessageContaining("base64: invalid input");
        assertThat(shell.launchScripts).hasSize(1);
    }

    @Test
    void launchScriptsAreIdempotentAndDetached() {
        String launch = RemoteJobRunner.load("scripts/job-launch.sh");
        assertThat(launch)
                .contains("if [ -d \"$DIR\" ]; then")
                .contains("systemd-run --quiet --collect")
                .contains("setsid nohup")
                .contains("rm -f ./script.sh")
                .contains("umask 077");
        assertThat(RemoteJobRunner.load("scripts/job-poll.sh")).contains("JOB_LOST").contains("kill -0");
    }
}
