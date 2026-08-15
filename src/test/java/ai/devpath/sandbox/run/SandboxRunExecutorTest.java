package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    CountDownLatch finished = new CountDownLatch(1);

    executor.submit(77L, () -> finished::countDown);
    assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();

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
}
