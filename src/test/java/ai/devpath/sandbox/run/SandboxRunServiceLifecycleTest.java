package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;

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
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    executor = new SandboxRunExecutor(1, 1, 2_000, metrics);
    SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(persistence, metrics, 3);
    SandboxRunService service = new SandboxRunService(persistence, backend, executor, finalizer);
    SandboxSession allocated = mock(SandboxSession.class);
    SandboxSession terminal = mock(SandboxSession.class);
    when(allocated.getId()).thenReturn(91L);
    when(persistence.allocate(anyLong(), any())).thenReturn(allocated);
    when(persistence.markRunning(91L)).thenReturn(true);
    CountDownLatch runnerStarted = new CountDownLatch(1);
    CountDownLatch releaseRunner = new CountDownLatch(1);
    when(persistence.attachContainer(91L, "container-91")).thenReturn(true);
    when(backend.run(any(), any(), any())).thenAnswer(inv -> {
      java.util.function.Consumer<String> containerCreated = inv.getArgument(2);
      containerCreated.accept("container-91");
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
    order.verify(backend).run(any(), any(), any());
    order.verify(persistence).attachContainer(91L, "container-91");
    order.verify(persistence).finish(anyLong(), any());
  }

  @Test
  void drainDurablyKillsQueuedAcceptedWorkAndCompletesItsDeliveryBeforeReturning()
      throws Exception {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    RunnerBackend backend = mock(RunnerBackend.class);
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    executor = new SandboxRunExecutor(1, 1, 2_000, metrics);
    SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(persistence, metrics, 1);
    SandboxRunService service = new SandboxRunService(persistence, backend, executor, finalizer);
    java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong(200L);
    when(persistence.allocate(anyLong(), any())).thenAnswer(invocation -> {
      SandboxSession allocated = mock(SandboxSession.class);
      when(allocated.getId()).thenReturn(ids.incrementAndGet());
      return allocated;
    });
    when(persistence.markRunning(201L)).thenReturn(true);
    CountDownLatch runnerStarted = new CountDownLatch(1);
    when(backend.run(any(), any(), any())).thenAnswer(invocation -> {
      runnerStarted.countDown();
      try {
        new CountDownLatch(1).await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new SandboxUnavailableException("shutdown", interrupted);
      }
      return new RunResult(0, "", "", null, null);
    });
    when(persistence.finish(anyLong(), any())).thenAnswer(invocation -> {
      long sessionId = invocation.getArgument(0);
      RunResult result = invocation.getArgument(1);
      SandboxSession terminal = mock(SandboxSession.class);
      when(terminal.getId()).thenReturn(sessionId);
      when(terminal.getStatus()).thenReturn(result.terminalStatus().name());
      when(terminal.getExitCode()).thenReturn(result.exitCode());
      when(terminal.isOutputTruncated()).thenReturn(result.outputTruncated());
      return terminal;
    });
    SandboxRunDelivery ignoredDelivery = noOpDelivery();
    CountDownLatch queuedCompleted = new CountDownLatch(1);
    SandboxRunDelivery queuedDelivery = new SandboxRunDelivery() {
      @Override public void session(long sessionId) {}
      @Override public void log(String line) {}
      @Override public void result(SandboxTerminalEvent event) {}
      @Override public void complete() { queuedCompleted.countDown(); }
    };

    service.start(1L, new SandboxRunRequest("first", "PYTHON", null, null), ignoredDelivery);
    assertThat(runnerStarted.await(1, TimeUnit.SECONDS)).isTrue();
    AcceptedSandboxRun queued = service.start(
        2L, new SandboxRunRequest("queued", "PYTHON", null, null), queuedDelivery);

    executor.stopForTest(Duration.ofMillis(300));

    assertThat(queued.sessionId()).isEqualTo(202L);
    assertThat(queuedCompleted.await(1, TimeUnit.SECONDS)).isTrue();
    verify(persistence, atLeastOnce()).finish(eq(202L),
        org.mockito.ArgumentMatchers.argThat(
            result -> result.terminalStatus() == SandboxTerminalStatus.KILLED));
  }

  private static SandboxRunDelivery noOpDelivery() {
    return new SandboxRunDelivery() {
      @Override public void session(long sessionId) {}
      @Override public void log(String line) {}
      @Override public void result(SandboxTerminalEvent event) {}
      @Override public void complete() {}
    };
  }
}
