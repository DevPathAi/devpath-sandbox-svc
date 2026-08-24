package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SandboxReleaseStaleFixtureServiceTest {
  @Test
  void seedsExpiredRowsAndRunsTheRealTwoPassReconciler() {
    String candidate = "a".repeat(64);
    String runKey = "R".repeat(43);
    SandboxSessionRepository sessions = mock(SandboxSessionRepository.class);
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    SandboxReleaseFaultRegistry registry = mock(SandboxReleaseFaultRegistry.class);
    SandboxSession saved = mock(SandboxSession.class);
    SandboxSession terminal = mock(SandboxSession.class);
    when(saved.getId()).thenReturn(81L);
    when(terminal.getStatus()).thenReturn("FAILED");
    when(terminal.getTerminalSource()).thenReturn("RECONCILER");
    when(sessions.saveAndFlush(any())).thenReturn(saved);
    when(sessions.findById(81L)).thenReturn(Optional.of(terminal));

    var result = new SandboxReleaseStaleFixtureService(sessions, persistence, registry)
        .seed(candidate, runKey, 42L, "ALLOCATING");

    ArgumentCaptor<SandboxSession> fixture = ArgumentCaptor.forClass(SandboxSession.class);
    verify(sessions).saveAndFlush(fixture.capture());
    assertThat(fixture.getValue().getUserId()).isEqualTo(42L);
    assertThat(fixture.getValue().getStatus()).isEqualTo("ALLOCATING");
    assertThat(fixture.getValue().getLeaseExpiresAt()).isBefore(java.time.Instant.now());
    ArgumentCaptor<java.time.Instant> reconciliationTimes =
        ArgumentCaptor.forClass(java.time.Instant.class);
    verify(persistence, times(2)).reconcileExpired(
        reconciliationTimes.capture(), any(), anyInt());
    assertThat(reconciliationTimes.getAllValues().get(1))
        .isAfter(reconciliationTimes.getAllValues().get(0));
    verify(registry).recordStaleReconciliation(
        candidate, runKey, "ALLOCATING", true);
    assertThat(result.passed()).isTrue();
    assertThat(result.sessionId()).isEqualTo(81L);
  }
}
