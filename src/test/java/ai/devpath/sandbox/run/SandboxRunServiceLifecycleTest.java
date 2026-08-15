package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SandboxRunServiceLifecycleTest {

  private SandboxRunExecutor executor;

  @AfterEach
  void stopExecutor() {
    if (executor != null) {
      executor.stopForTest(Duration.ofSeconds(2));
    }
  }

  @Test
  void returnsAcceptedIdentityBeforeRunnerCompletesAndPersistsTerminalDespiteDeliveryFailure()
      throws Exception {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    RunnerBackend backend = mock(RunnerBackend.class);
    executor = new SandboxRunExecutor(1, 1, 2_000, new SimpleMeterRegistry());
    SandboxRunService service = new SandboxRunService(persistence, backend, executor);
    SandboxSession allocated = mock(SandboxSession.class);
    SandboxSession terminal = mock(SandboxSession.class);
    when(allocated.getId()).thenReturn(91L);
    when(persistence.allocate(anyLong(), any())).thenReturn(allocated);
    when(persistence.markRunning(91L)).thenReturn(true);
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    when(backend.run(any(), any())).thenAnswer(inv -> {
      runnerStarted.countDown();
      releaseRunner.await();
      return new RunResult(0, "ok", "", null, null);
    });
    when(persistence.finish(anyLong(), any())).thenReturn(terminal);
    when(terminal.getId()).thenReturn(91L);
    when(terminal.getStatus()).thenReturn("COMPLETED");
    when(terminal.getExitCode()).thenReturn(0);
    SandboxRunDelivery brokenDelivery = new SandboxRunDelivery() {
      @Override public void session(long sessionId) { throw new IllegalStateException("gone"); }
      @Override public void log(String line) { throw new IllegalStateException("gone"); }
      @Override public void result(SandboxTerminalEvent event) { throw new IllegalStateException("gone"); }
      @Override public void complete() { throw new IllegalStateException("gone"); }
    };

    AcceptedSandboxRun accepted = service.start(42L,
        new SandboxRunRequest("print(1)", "PYTHON", null, null), brokenDelivery);

    assertThat(accepted.sessionId()).isEqualTo(91L);
    assertThat(runnerStarted.await(1, TimeUnit.SECONDS)).isTrue();
    releaseRunner.countDown();
    verify(persistence, org.mockito.Mockito.timeout(1_000)).finish(anyLong(), any());
    InOrder order = inOrder(persistence, backend);
    order.verify(persistence).allocate(anyLong(), any());
    order.verify(persistence).markRunning(91L);
    order.verify(backend).run(any(), any());
    order.verify(persistence).finish(anyLong(), any());
  }
}
