package com.nebdev.devopsagent.ssh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployLockTest {

    @Test
    void secondAcquireOnSameHostFails() {
        DeployLock lock = new DeployLock();
        assertThat(lock.tryAcquire("1.2.3.4", "job-a", "deploy-platform", "arc")).isEmpty();
        var conflict = lock.tryAcquire("1.2.3.4", "job-b", "deploy-app", "other");
        assertThat(conflict).isPresent();
        assertThat(conflict.get().jobId()).isEqualTo("job-a");
        assertThat(conflict.get().label()).isEqualTo("arc");
    }

    @Test
    void releaseAllowsNext() {
        DeployLock lock = new DeployLock();
        lock.tryAcquire("Host.Example", "j1", "deploy-app", "a");
        lock.release("j1");
        assertThat(lock.current("host.example")).isEmpty();
        assertThat(lock.tryAcquire("host.example", "j2", "deploy-platform", "p")).isEmpty();
    }

    @Test
    void differentHostsIndependent() {
        DeployLock lock = new DeployLock();
        assertThat(lock.tryAcquire("10.0.0.1", "a", "deploy-app", "x")).isEmpty();
        assertThat(lock.tryAcquire("10.0.0.2", "b", "deploy-app", "y")).isEmpty();
        assertThat(lock.all()).hasSize(2);
    }

    @Test
    void busyExceptionMentionsHost() {
        DeployLock.Holder h = new DeployLock.Holder("j", "deploy-platform", "arc", "1.2.3.4",
                java.time.Instant.parse("2026-11-02T10:00:00Z"));
        assertThatThrownBy(() -> {
            throw new DeployBusyException(h);
        }).isInstanceOf(DeployBusyException.class)
                .hasMessageContaining("1.2.3.4")
                .hasMessageContaining("arc");
    }
}
