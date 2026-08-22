package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class SandboxRunnerHealthIndicatorTest {

  @Test
  void requestPathReadsCachedStateWithoutPingingRunner() {
    RunnerBackend backend = mock(RunnerBackend.class);
    when(backend.isAvailable()).thenReturn(true);
    SandboxRunnerHealthIndicator health =
        new SandboxRunnerHealthIndicator(backend, new SimpleMeterRegistry());

    assertThat(health.isAvailable()).isFalse();
    verifyNoInteractions(backend);

    health.refresh();

    assertThat(health.isAvailable()).isTrue();
    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    verify(backend).isAvailable();
  }

  @Test
  void readinessFailsClosedWhileTerminalResultHeadroomIsExhaustedAndRecovers() {
    RunnerBackend backend = mock(RunnerBackend.class);
    when(backend.isAvailable()).thenReturn(true);
    SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(
        mock(SandboxRunPersistenceService.class), new SimpleMeterRegistry(), 1, 1,
        SandboxTerminalFinalizer.RESULT_RESERVATION_BYTES, java.time.Duration.ofSeconds(1));
    SandboxRunnerHealthIndicator health =
        new SandboxRunnerHealthIndicator(backend, finalizer, new SimpleMeterRegistry());
    health.refresh();

    var reservation = finalizer.reserve();

    assertThat(health.isAvailable()).isFalse();
    assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    reservation.close();
    assertThat(health.isAvailable()).isTrue();
    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
  }
}
