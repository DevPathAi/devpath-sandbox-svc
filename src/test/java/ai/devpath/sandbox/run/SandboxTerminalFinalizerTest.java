package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.sql.SQLTransientConnectionException;
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

  @Test
  void reservedHeadroomBoundsPendingResultsAndRecoversWithoutEviction() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    RunResult exact = new RunResult(
        SandboxTerminalStatus.COMPLETED, 0, "x".repeat(1_024), "", null, null, false);
    when(persistence.finish(91L, exact))
        .thenThrow(new IllegalStateException(
            "database down", new SQLTransientConnectionException("database down")))
        .thenReturn(mock(SandboxSession.class));
    when(persistence.finishWithoutEvent(91L, exact))
        .thenThrow(new IllegalStateException(
            "database down", new SQLTransientConnectionException("database down")));
    SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(
        persistence, new SimpleMeterRegistry(), 1, 1,
        SandboxTerminalFinalizer.RESULT_RESERVATION_BYTES, Duration.ofSeconds(1));
    SandboxTerminalFinalizer.Reservation reservation = finalizer.reserve();

    assertThat(finalizer.hasCapacity()).isFalse();
    assertThrows(SandboxUnavailableException.class, finalizer::reserve);
    assertThrows(RuntimeException.class, () -> finalizer.finish(91L, exact, reservation));
    assertThat(finalizer.pendingCount()).isEqualTo(1);
    assertThat(finalizer.hasCapacity()).isFalse();

    assertThat(finalizer.retryPending()).isEqualTo(1);
    assertThat(finalizer.pendingCount()).isZero();
    assertThat(finalizer.hasCapacity()).isTrue();
  }

  @Test
  void nonTransientFailureDoesNotBurnTheRetryLoopAndExactResultStaysReserved() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    RunResult exact = new RunResult(1, "", "compile", null, null);
    when(persistence.finish(92L, exact)).thenThrow(new IllegalArgumentException("mapping"));
    when(persistence.finishWithoutEvent(92L, exact))
        .thenThrow(new IllegalArgumentException("mapping"));
    SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(
        persistence, new SimpleMeterRegistry(), 5, 1,
        SandboxTerminalFinalizer.RESULT_RESERVATION_BYTES, Duration.ofSeconds(1));
    var reservation = finalizer.reserve();

    assertThrows(IllegalArgumentException.class,
        () -> finalizer.finish(92L, exact, reservation));

    verify(persistence).finish(92L, exact);
    verify(persistence).finishWithoutEvent(92L, exact);
    assertThat(finalizer.pendingCount()).isEqualTo(1);
  }

  @Test
  void elapsedRetryBudgetStopsTransientRetryBurstButRetainsExactResult() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    RunResult exact = RunResult.timedOut("partial", "deadline");
    when(persistence.finish(93L, exact)).thenAnswer(invocation -> {
      Thread.sleep(25L);
      throw new IllegalStateException(
          "slow outage", new SQLTransientConnectionException("slow outage"));
    });
    when(persistence.finishWithoutEvent(93L, exact)).thenThrow(
        new IllegalStateException("outage", new SQLTransientConnectionException("outage")));
    SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(
        persistence, new SimpleMeterRegistry(), 5, 1,
        SandboxTerminalFinalizer.RESULT_RESERVATION_BYTES, Duration.ofMillis(10));
    var reservation = finalizer.reserve();

    assertThrows(RuntimeException.class, () -> finalizer.finish(93L, exact, reservation));

    verify(persistence).finish(93L, exact);
    assertThat(finalizer.pendingCount()).isEqualTo(1);
  }

  @Test
  void compatibilityReplayPersistsTheRetainedExactResultAndReleasesBothReservations() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    RunResult exact = new RunResult(
        SandboxTerminalStatus.TIMED_OUT, -1, "partial", "deadline", 30_000L, 48, true);
    RunResult lateWrongGuess = new RunResult(
        SandboxTerminalStatus.FAILED, 1, "", "wrong", null, null, false);
    when(persistence.finish(94L, exact))
        .thenThrow(new IllegalStateException("database down"))
        .thenReturn(mock(SandboxSession.class));
    when(persistence.finishWithoutEvent(94L, exact))
        .thenThrow(new IllegalStateException("database down"));
    SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(
        persistence, new SimpleMeterRegistry(), 1, 2,
        2L * SandboxTerminalFinalizer.RESULT_RESERVATION_BYTES, Duration.ofSeconds(1));
    var firstReservation = finalizer.reserve();
    assertThrows(RuntimeException.class,
        () -> finalizer.finish(94L, exact, firstReservation));

    SandboxSession saved = finalizer.finish(94L, lateWrongGuess);

    assertThat(saved).isNotNull();
    assertThat(finalizer.pendingCount()).isZero();
    verify(persistence, org.mockito.Mockito.times(2)).finish(94L, exact);
    verify(persistence, org.mockito.Mockito.never()).finish(94L, lateWrongGuess);
    var first = finalizer.reserve();
    var second = finalizer.reserve();
    assertThrows(SandboxUnavailableException.class, finalizer::reserve);
    first.close();
    second.close();
    assertThat(finalizer.hasCapacity()).isTrue();
  }

  @Test
  void scheduledRecoveryLimitsEachRemoteDatabaseCycleToTheConfiguredBatch() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    when(persistence.finish(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("database down"));
    when(persistence.finishWithoutEvent(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("database down"));
    SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(
        persistence, new SimpleMeterRegistry(), 1, 3,
        3L * SandboxTerminalFinalizer.RESULT_RESERVATION_BYTES,
        Duration.ofMillis(50), 1);
    for (long sessionId = 101L; sessionId <= 103L; sessionId++) {
      var reservation = finalizer.reserve();
      long id = sessionId;
      assertThrows(RuntimeException.class,
          () -> finalizer.finish(id, RunResult.timedOut("", "down"), reservation));
    }
    org.mockito.Mockito.clearInvocations(persistence);

    assertThat(finalizer.retryPending()).isZero();

    verify(persistence).finish(anyLong(), org.mockito.ArgumentMatchers.any());
    verify(persistence).finishWithoutEvent(anyLong(), org.mockito.ArgumentMatchers.any());
    assertThat(finalizer.pendingCount()).isEqualTo(3);
  }
}
