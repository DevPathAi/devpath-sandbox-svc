package ai.devpath.sandbox.run;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Durable lease heartbeat isolated from every shared Spring or remote-work scheduler. */
@Component
public class SandboxExecutionLeaseHeartbeat implements SmartLifecycle {

  private final SandboxRunPersistenceService persistence;
  private final Duration interval;
  private volatile ScheduledExecutorService scheduler;
  private final AtomicBoolean running = new AtomicBoolean();

  @Autowired
  public SandboxExecutionLeaseHeartbeat(
      SandboxRunPersistenceService persistence,
      @Value("${devpath.sandbox.lease.heartbeat-ms:5000}") long intervalMs) {
    this(persistence, Duration.ofMillis(intervalMs));
  }

  SandboxExecutionLeaseHeartbeat(
      SandboxRunPersistenceService persistence,
      Duration interval) {
    if (interval == null || interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException("Lease heartbeat interval must be positive");
    }
    this.persistence = persistence;
    this.interval = interval;
  }

  private static ScheduledExecutorService newScheduler() {
    return Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "sandbox-lease-heartbeat");
      thread.setDaemon(true);
      return thread;
    });
  }

  SandboxExecutionLeaseHeartbeat(SandboxRunPersistenceService persistence) {
    this(persistence, Duration.ofSeconds(5));
  }

  public int renew() {
    return persistence.renewOwnedLeases();
  }

  @Override
  public void start() {
    if (running.compareAndSet(false, true)) {
      scheduler = newScheduler();
      scheduler.scheduleWithFixedDelay(
          this::renewSafely,
          0L,
          interval.toMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void stop() {
    if (running.getAndSet(false)) {
      ScheduledExecutorService active = scheduler;
      scheduler = null;
      if (active != null) {
        active.shutdownNow();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public int getPhase() {
    // Lower phases stop later, so leases remain live throughout the runner's drain.
    return Integer.MIN_VALUE;
  }

  private void renewSafely() {
    if (!running.get()) {
      return;
    }
    try {
      renew();
    } catch (RuntimeException ignored) {
      // A failed renewal leaves the old expiry intact; exact results remain fenced in-process.
    }
  }
}
