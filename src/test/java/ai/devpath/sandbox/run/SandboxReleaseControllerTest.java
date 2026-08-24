package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxReleaseControllerTest {
  @Test
  void armsFaultSeedsStaleRowsAndReportsOnlyExactCheckpoints() {
    String candidate = "a".repeat(64);
    String runKey = "R".repeat(43);
    SandboxReleaseFaultRegistry registry = mock(SandboxReleaseFaultRegistry.class);
    SandboxReleaseStaleFixtureService stale = mock(SandboxReleaseStaleFixtureService.class);
    SandboxReleaseController controller = new SandboxReleaseController(registry, stale);

    assertThat(controller.command(
        candidate, runKey, "next-run-timeout", Map.of()).get("accepted"))
        .isEqualTo(true);
    verify(registry).arm(candidate, runKey, "next-run-timeout");

    when(stale.seed(candidate, runKey, 42L, "RUNNING"))
        .thenReturn(new SandboxReleaseStaleFixtureService.Seeded(82L, true));
    assertThat(controller.command(
        candidate, runKey, "seed-stale-running", Map.of("user_id", 42L)))
        .containsEntry("accepted", true);
    verify(stale).seed(candidate, runKey, 42L, "RUNNING");

    when(registry.checkpoint(
        candidate, runKey, "stale-running-reconciled")).thenReturn(true);
    assertThat(controller.checkpoint(
        candidate, runKey, "stale-running-reconciled"))
        .containsEntry("passed", true);
  }
}
