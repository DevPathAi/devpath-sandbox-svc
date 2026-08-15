package ai.devpath.sandbox.run;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable execution heartbeat; deliberately separate from the best-effort SSE heartbeat. */
@Component
public class SandboxExecutionLeaseHeartbeat {

  private final SandboxRunPersistenceService persistence;

  public SandboxExecutionLeaseHeartbeat(SandboxRunPersistenceService persistence) {
    this.persistence = persistence;
  }

  @Scheduled(
      initialDelayString = "${devpath.sandbox.lease.heartbeat-ms:5000}",
      fixedDelayString = "${devpath.sandbox.lease.heartbeat-ms:5000}")
  public int renew() {
    return persistence.renewOwnedLeases();
  }
}
