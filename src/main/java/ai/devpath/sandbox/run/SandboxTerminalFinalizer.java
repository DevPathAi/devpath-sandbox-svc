package ai.devpath.sandbox.run;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Map;

/** Bounded atomic terminal persistence with an accurate terminal-only fallback. */
@Component
public class SandboxTerminalFinalizer {

  private final SandboxRunPersistenceService persistence;
  private final int attempts;
  private final Counter atomicFailures;
  private final Counter fallbackFailures;
  private final Counter fallbacks;
  private final Counter deferred;
  private final ConcurrentMap<Long, RunResult> pending = new ConcurrentHashMap<>();

  public SandboxTerminalFinalizer(
      SandboxRunPersistenceService persistence,
      MeterRegistry metrics,
      @Value("${devpath.sandbox.terminal-persistence-attempts:3}") int attempts) {
    if (attempts < 1) {
      throw new IllegalArgumentException("Terminal persistence attempts must be positive");
    }
    this.persistence = persistence;
    this.attempts = attempts;
    this.atomicFailures = Counter.builder("sandbox.runs.terminal_persistence_failures")
        .tag("stage", "atomic").register(metrics);
    this.fallbackFailures = Counter.builder("sandbox.runs.terminal_persistence_failures")
        .tag("stage", "fallback").register(metrics);
    this.fallbacks = Counter.builder("sandbox.runs.terminal_fallback").register(metrics);
    this.deferred = Counter.builder("sandbox.runs.terminal_deferred").register(metrics);
    Gauge.builder("sandbox.runs.terminal_pending", pending, ConcurrentMap::size)
        .register(metrics);
  }

  public SandboxSession finish(long sessionId, RunResult result) {
    RuntimeException lastFailure = null;
    for (int attempt = 0; attempt < attempts; attempt++) {
      try {
        SandboxSession saved = persistence.finish(sessionId, result);
        pending.remove(sessionId, result);
        return saved;
      } catch (RuntimeException failure) {
        atomicFailures.increment();
        lastFailure = failure;
      }
    }

    fallbacks.increment();
    for (int attempt = 0; attempt < attempts; attempt++) {
      try {
        SandboxSession saved = persistence.finishWithoutEvent(sessionId, result);
        pending.remove(sessionId, result);
        return saved;
      } catch (RuntimeException failure) {
        fallbackFailures.increment();
        lastFailure = failure;
      }
    }
    pending.put(sessionId, result);
    deferred.increment();
    throw lastFailure == null
        ? new IllegalStateException("Terminal persistence failed without a cause")
        : lastFailure;
  }

  @Scheduled(
      initialDelayString = "${devpath.sandbox.terminal-retry-delay-ms:5000}",
      fixedDelayString = "${devpath.sandbox.terminal-retry-delay-ms:5000}")
  public int retryPending() {
    int finalized = 0;
    for (var entry : Map.copyOf(pending).entrySet()) {
      try {
        finish(entry.getKey(), entry.getValue());
        finalized++;
      } catch (RuntimeException stillUnavailable) {
        // finish keeps the exact RunResult in pending for the next bounded cycle.
      }
    }
    return finalized;
  }

  int pendingCount() {
    return pending.size();
  }
}
