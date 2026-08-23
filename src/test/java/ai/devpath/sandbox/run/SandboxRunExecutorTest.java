package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SandboxRunExecutorTest {

  private SandboxRunExecutor executor;

  @AfterEach
  void closeExecutor() {
    if (executor != null) {
      executor.stopForTest(Duration.ofSeconds(2));
    }
  }

  @Test
  void rejectsSecondActiveRunForSameUser() throws Exception {
    executor = new SandboxRunExecutor(1, 1, 2_000, new SimpleMeterRegistry());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    executor.submit(41L, () -> blockingWork(started, release));
    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

    assertThrows(SandboxBusyException.class,
        () -> executor.submit(41L, () -> () -> {}));
    assertThrows(SandboxBusyException.class, () -> executor.assertCanAdmit(41L));

    release.countDown();
  }

  @Test
  void rejectsCapacityPlusOneWithoutUsingTheCommonPool() throws Exception {
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    executor = new SandboxRunExecutor(1, 1, 2_000, metrics);
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    executor.submit(1L, () -> blockingWork(firstStarted, release));
    assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
    executor.submit(2L, () -> () -> {});

    assertThrows(SandboxBusyException.class,
        () -> executor.submit(3L, () -> () -> {}));
    assertThat(metrics.get("sandbox.runs.rejected")
        .tag("reason", "capacity").counter().count()).isEqualTo(1.0);

    release.countDown();
  }

  @Test
  void releasesUserAdmissionAfterWorkFinishes() throws Exception {
    executor = new SandboxRunExecutor(1, 0, 2_000, new SimpleMeterRegistry());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch allowReturn = new CountDownLatch(1);

    executor.submit(77L, () -> () -> {
      started.countDown();
      try {
        allowReturn.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
      assertThrows(SandboxBusyException.class,
          () -> executor.submit(77L, () -> () -> {}));
    } finally {
      allowReturn.countDown();
    }

    awaitAdmissionRelease(77L);
    CountDownLatch replayFinished = new CountDownLatch(1);
    executor.submit(77L, () -> replayFinished::countDown);
    assertThat(replayFinished.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void terminalAndTruncationMetricsUseOnlyBoundedStatusTags() {
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    executor = new SandboxRunExecutor(1, 0, 2_000, metrics);
    SandboxSession terminal = mock(SandboxSession.class);
    when(terminal.getStatus()).thenReturn("TIMED_OUT");
    when(terminal.isOutputTruncated()).thenReturn(true);

    executor.recordTerminal(terminal);

    assertThat(metrics.get("sandbox.runs.terminal")
        .tag("status", "TIMED_OUT").counter().count()).isEqualTo(1.0);
    assertThat(metrics.get("sandbox.runs.truncated").counter().count()).isEqualTo(1.0);
  }

  @Test
  void shutdownCancelsQueuedAcceptedWorkAndRunsItsFinalizerWithinTheBudget() throws Exception {
    executor = new SandboxRunExecutor(1, 1, 2_000, new SimpleMeterRegistry());
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch queuedCancelled = new CountDownLatch(1);
    AtomicBoolean queuedRan = new AtomicBoolean();
    executor.submit(1L, () -> blockingWork(firstStarted, release));
    assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
    executor.submit(2L, () -> new SandboxRunExecutor.CancelableWork() {
      @Override public void run() { queuedRan.set(true); }
      @Override public void cancelBeforeStart() { queuedCancelled.countDown(); }
    });

    long startedAt = System.nanoTime();
    executor.stopForTest(Duration.ofMillis(250));
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(queuedCancelled.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(queuedRan).isFalse();
    assertThat(elapsedMs).isLessThan(400L);
    release.countDown();
  }

  @Test
  void shutdownCancelsRunningWorkAtTheActiveCutoffAndKeepsATerminalTailBudget()
      throws Exception {
    executor = new SandboxRunExecutor(1, 0, 300, 100, new SimpleMeterRegistry());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch remoteCancelled = new CountDownLatch(1);
    CountDownLatch terminalPersisted = new CountDownLatch(1);
    executor.submit(9L, () -> new SandboxRunExecutor.CancelableWork() {
      @Override
      public void run() {
        started.countDown();
        try {
          remoteCancelled.await();
          Thread.sleep(50L);
          terminalPersisted.countDown();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }

      @Override public void cancelBeforeStart() {}

      @Override public void cancelRunning() { remoteCancelled.countDown(); }
    });
    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

    long startedAt = System.nanoTime();
    executor.stopForTest(Duration.ofMillis(300));
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(remoteCancelled.getCount()).isZero();
    assertThat(terminalPersisted.getCount()).isZero();
    assertThat(elapsedMs).isBetween(80L, 290L);
  }

  private static Runnable blockingWork(CountDownLatch started, CountDownLatch release) {
    return () -> {
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
  }

  private void awaitAdmissionRelease(long userId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (System.nanoTime() < deadline) {
      try {
        executor.assertCanAdmit(userId);
        return;
      } catch (SandboxBusyException ignored) {
        Thread.sleep(1L);
      }
    }
    executor.assertCanAdmit(userId);
  }
}
