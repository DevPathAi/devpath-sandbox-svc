package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SandboxExecutionLeaseHeartbeatTest {

  @Test
  void heartbeatRenewsEveryOwnedQueuedAndRunningLeaseIndependentlyOfSse() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    when(persistence.renewOwnedLeases()).thenReturn(3);
    SandboxExecutionLeaseHeartbeat heartbeat = new SandboxExecutionLeaseHeartbeat(persistence);

    int renewed = heartbeat.renew();

    assertThat(renewed).isEqualTo(3);
    verify(persistence).renewOwnedLeases();
  }

  @Test
  void dedicatedLeaseThreadKeepsRenewingWhileTheSharedSchedulerIsBlocked() throws Exception {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    CountDownLatch renewed = new CountDownLatch(2);
    AtomicReference<String> renewalThread = new AtomicReference<>();
    when(persistence.renewOwnedLeases()).thenAnswer(invocation -> {
      renewalThread.set(Thread.currentThread().getName());
      renewed.countDown();
      return 1;
    });
    CountDownLatch releaseSharedScheduler = new CountDownLatch(1);
    try (var sharedScheduler = Executors.newSingleThreadExecutor()) {
      sharedScheduler.submit(() -> {
        releaseSharedScheduler.await();
        return null;
      });
      SandboxExecutionLeaseHeartbeat heartbeat =
          new SandboxExecutionLeaseHeartbeat(persistence, Duration.ofMillis(15));
      try {
        heartbeat.start();
        assertThat(renewed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(renewalThread.get()).startsWith("sandbox-lease-heartbeat");
      } finally {
        heartbeat.stop();
        releaseSharedScheduler.countDown();
      }
    }
  }

  @Test
  void heartbeatStopsAfterTheRunnerDrainPhase() {
    SandboxExecutionLeaseHeartbeat heartbeat =
        new SandboxExecutionLeaseHeartbeat(mock(SandboxRunPersistenceService.class));
    SandboxRunExecutor executor = new SandboxRunExecutor(
        1, 0, 100, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    try {
      assertThat(heartbeat.getPhase()).isLessThan(executor.getPhase());
    } finally {
      executor.stopForTest(Duration.ofMillis(100));
    }
  }
}
