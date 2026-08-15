package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SandboxOrphanReaperTest {

  @Test
  void delegatesDeadlineCleanupToTheIsolatedRunnerBackend() {
    RunnerBackend backend = mock(RunnerBackend.class);
    when(backend.reapExpiredContainers(org.mockito.ArgumentMatchers.any())).thenReturn(2);
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    SandboxOrphanReaper reaper = new SandboxOrphanReaper(backend, metrics);

    int reaped = reaper.reap(Instant.parse("2026-08-16T00:00:31Z"));

    assertThat(reaped).isEqualTo(2);
    verify(backend).reapExpiredContainers(Instant.parse("2026-08-16T00:00:31Z"));
    assertThat(metrics.get("sandbox.runner.orphans.reaped").counter().count()).isEqualTo(2);
  }
}
