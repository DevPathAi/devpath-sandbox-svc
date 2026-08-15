package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class SandboxExecutionLeaseHeartbeatTest {

  @Test
  void heartbeatRenewsEveryOwnedQueuedAndRunningLeaseIndependentlyOfSse() {
    SandboxRunPersistenceService persistence = mock(SandboxRunPersistenceService.class);
    when(persistence.renewOwnedLeases()).thenReturn(3);
    SandboxExecutionLeaseHeartbeat heartbeat = new SandboxExecutionLeaseHeartbeat(persistence);

    int renewed = heartbeat.renew();

    assertThat(renewed).isEqualTo(3);
    verify(persistence).renewOwnedLeases();
  }
}
