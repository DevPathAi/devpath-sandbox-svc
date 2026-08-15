package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class SandboxTerminalFinalizerTest {

  @Test
  void boundedAtomicRetriesFallBackToTheExactRunnerResult() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    RunResult actual = new RunResult(
        SandboxTerminalStatus.TIMED_OUT, -1, "partial", "deadline", 29_000L, 127, true);
    RuntimeException outboxFailure = new IllegalStateException("outbox unavailable");
    when(persistence.finish(77L, actual))
        .thenThrow(outboxFailure)
        .thenThrow(outboxFailure)
        .thenThrow(outboxFailure);
    SandboxSession terminal = mock(SandboxSession.class);
    when(persistence.finishWithoutEvent(77L, actual)).thenReturn(terminal);
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    SandboxTerminalFinalizer finalizer =
        new SandboxTerminalFinalizer(persistence, metrics, 3);

    SandboxSession saved = finalizer.finish(77L, actual);

    assertThat(saved).isSameAs(terminal);
    verify(persistence, org.mockito.Mockito.times(3)).finish(77L, actual);
    verify(persistence).finishWithoutEvent(77L, actual);
    assertThat(metrics.get("sandbox.runs.terminal_persistence_failures")
        .tag("stage", "atomic").counter().count()).isEqualTo(3);
    assertThat(metrics.get("sandbox.runs.terminal_fallback").counter().count()).isEqualTo(1);
  }

  @Test
  void totalDatabaseOutageRetainsExactResultForBackgroundRetry() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    RunResult actual = new RunResult(
        SandboxTerminalStatus.COMPLETED, 0, "actual", "", 20L, 32, false);
    java.util.concurrent.atomic.AtomicInteger atomicCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    SandboxSession terminal = mock(SandboxSession.class);
    when(persistence.finish(88L, actual)).thenAnswer(invocation -> {
      if (atomicCalls.incrementAndGet() <= 3) {
        throw new IllegalStateException("database down");
      }
      return terminal;
    });
    when(persistence.finishWithoutEvent(88L, actual))
        .thenThrow(new IllegalStateException("database down"));
    SandboxTerminalFinalizer finalizer =
        new SandboxTerminalFinalizer(persistence, new SimpleMeterRegistry(), 3);

    assertThrows(IllegalStateException.class, () -> finalizer.finish(88L, actual));
    assertThat(finalizer.pendingCount()).isEqualTo(1);

    assertThat(finalizer.retryPending()).isEqualTo(1);
    assertThat(finalizer.pendingCount()).isZero();
    verify(persistence, org.mockito.Mockito.atLeastOnce()).finish(88L, actual);
  }
}
