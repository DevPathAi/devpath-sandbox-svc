package ai.devpath.sandbox.run;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SandboxRunReconciler {

  private final SandboxRunPersistenceService persistence;
  private final long staleAfterMs;
  private final int batchSize;
  private final AtomicLong expiredActive = new AtomicLong();

  public SandboxRunReconciler(
      SandboxRunPersistenceService persistence,
      @Value("${devpath.sandbox.reconcile.stale-after-ms:35000}") long staleAfterMs,
      @Value("${devpath.sandbox.reconcile.batch-size:100}") int batchSize,
      MeterRegistry metrics) {
    this.persistence = persistence;
    this.staleAfterMs = staleAfterMs;
    this.batchSize = batchSize;
    Gauge.builder("sandbox.runs.expired_active", expiredActive, AtomicLong::get)
        .description("Accepted Sandbox rows whose durable execution lease has expired")
        .register(metrics);
  }

  @Scheduled(
      initialDelayString = "${devpath.sandbox.reconcile.initial-delay-ms:5000}",
      fixedDelayString = "${devpath.sandbox.reconcile.fixed-delay-ms:5000}")
  public void reconcile() {
    reconcileAt(Instant.now());
  }

  void reconcileAt(Instant now) {
    Instant legacyCutoff = now.minusMillis(staleAfterMs);
    persistence.reconcileExpired(now, now.minusMillis(staleAfterMs), batchSize);
    persistence.repairMissingTerminalEvents(batchSize);
    expiredActive.set(persistence.countExpiredActive(now, legacyCutoff));
  }
}
