package ai.devpath.sandbox.run;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SandboxRunReconciler {

  private final SandboxRunPersistenceService persistence;
  private final long staleAfterMs;

  public SandboxRunReconciler(
      SandboxRunPersistenceService persistence,
      @Value("${devpath.sandbox.reconcile.stale-after-ms:35000}") long staleAfterMs) {
    this.persistence = persistence;
    this.staleAfterMs = staleAfterMs;
  }

  @Scheduled(
      initialDelayString = "${devpath.sandbox.reconcile.initial-delay-ms:5000}",
      fixedDelayString = "${devpath.sandbox.reconcile.fixed-delay-ms:5000}")
  public void reconcile() {
    persistence.reconcileStale(Instant.now().minusMillis(staleAfterMs));
  }
}
