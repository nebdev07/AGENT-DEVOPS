package com.nebdev.devopsagent.ssh;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongJobTrackerTest {

    @Test
    void completesSuccessfully() throws Exception {
        LongJobTracker tracker = new LongJobTracker(new DeployLock());
        CountDownLatch done = new CountDownLatch(1);
        String id = tracker.start("deploy-platform", "arc", "1.2.3.4", () -> {
            done.countDown();
            return Map.of("status", "ok", "id", "arc");
        });
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        LongJobTracker.Job job = tracker.require(id);
        assertThat(job.state()).isEqualTo(LongJobTracker.State.SUCCEEDED);
        assertThat(job.result()).containsEntry("id", "arc");
        assertThat(job.toMap()).containsEntry("ok", true);
    }

    @Test
    void recordsFailure() throws Exception {
        DeployLock lock = new DeployLock();
        LongJobTracker tracker = new LongJobTracker(lock);
        String id = tracker.start("deploy-platform", "arc", "1.2.3.4", () -> {
            throw new IllegalStateException("boom");
        });
        for (int i = 0; i < 40; i++) {
            if (tracker.require(id).state() != LongJobTracker.State.RUNNING) {
                break;
            }
            Thread.sleep(25);
        }
        LongJobTracker.Job job = tracker.require(id);
        assertThat(job.state()).isEqualTo(LongJobTracker.State.FAILED);
        assertThat(job.error()).contains("boom");
        assertThat(job.toMap()).containsEntry("ok", false);
        assertThat(lock.current("1.2.3.4")).isEmpty();
    }

    @Test
    void refusesSecondJobOnSameHost() throws Exception {
        DeployLock lock = new DeployLock();
        LongJobTracker tracker = new LongJobTracker(lock);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        tracker.start("deploy-platform", "arc", "9.9.9.9", () -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Map.of("ok", true);
        });
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> tracker.start("deploy-app", "other", "9.9.9.9", () -> Map.of()))
                .isInstanceOf(DeployBusyException.class)
                .hasMessageContaining("arc");
        release.countDown();
    }
}
