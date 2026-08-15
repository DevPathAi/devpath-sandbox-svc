package ai.devpath.sandbox.run;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background runner probe. Request threads only read the cached atomic state. */
@Component("sandboxRunner")
public class SandboxRunnerHealthIndicator implements HealthIndicator {

  private final RunnerBackend backend;
  private final AtomicBoolean available = new AtomicBoolean(false);

  public SandboxRunnerHealthIndicator(RunnerBackend backend, MeterRegistry metrics) {
    this.backend = backend;
    Gauge.builder("sandbox.runner.available", available, value -> value.get() ? 1 : 0)
        .register(metrics);
  }

  public boolean isAvailable() {
    return available.get();
  }

  @Scheduled(
      initialDelayString = "${devpath.sandbox.runner.health-initial-delay-ms:0}",
      fixedDelayString = "${devpath.sandbox.runner.health-delay-ms:5000}")
  public void refresh() {
    boolean healthy;
    try {
      healthy = backend.isAvailable();
    } catch (RuntimeException failure) {
      healthy = false;
    }
    available.set(healthy);
  }

  @Override
  public Health health() {
    return available.get()
        ? Health.up().build()
        : Health.down().withDetail("reason", "isolated runner unavailable").build();
  }
}
