package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SandboxRunReconcilerTest {

  @Test
  void refreshesBoundedExpiredActiveGaugeAfterEveryReconciliationPass() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    SandboxRunReconciler reconciler = new SandboxRunReconciler(
        persistence, 35_000L, 100, metrics);
    Instant now = Instant.parse("2026-08-16T00:00:35Z");
    Instant legacyCutoff = Instant.parse("2026-08-16T00:00:00Z");
    when(persistence.countExpiredActive(now, legacyCutoff)).thenReturn(2L);

    reconciler.reconcileAt(now);

    verify(persistence).reconcileExpired(now, legacyCutoff, 100);
    verify(persistence).repairMissingTerminalEvents(100);
    assertThat(metrics.get("sandbox.runs.expired_active").gauge().value()).isEqualTo(2.0);
  }
}
