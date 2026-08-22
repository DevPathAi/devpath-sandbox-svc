package ai.devpath.sandbox.run;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes managed runner containers and source volumes whose deadline has elapsed. */
@Component
public class SandboxOrphanReaper {

  private final RunnerBackend backend;
  private final Counter reaped;
  private final Counter failures;

  public SandboxOrphanReaper(RunnerBackend backend, MeterRegistry metrics) {
    this.backend = backend;
    this.reaped = Counter.builder("sandbox.runner.orphans.reaped").register(metrics);
    this.failures = Counter.builder("sandbox.runner.orphans.failures").register(metrics);
  }

  @Scheduled(
      initialDelayString = "${devpath.sandbox.runner.reaper-initial-delay-ms:10000}",
      fixedDelayString = "${devpath.sandbox.runner.reaper-delay-ms:10000}")
  public void reap() {
    try {
      reap(Instant.now());
    } catch (RuntimeException failure) {
      failures.increment();
    }
  }

  int reap(Instant now) {
    int count = backend.reapExpiredContainers(now);
    reaped.increment(count);
    return count;
  }
}
