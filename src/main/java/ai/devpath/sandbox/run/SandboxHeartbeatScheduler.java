package ai.devpath.sandbox.run;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One bounded-purpose scheduler for SSE heartbeat comments. */
@Component
public class SandboxHeartbeatScheduler implements AutoCloseable {

  private final Duration interval;
  private final ScheduledExecutorService scheduler;

  @Autowired
  public SandboxHeartbeatScheduler(
      @Value("${devpath.sandbox.sse-heartbeat-ms:10000}") long intervalMs) {
    this(Duration.ofMillis(intervalMs));
  }

  SandboxHeartbeatScheduler(Duration interval) {
    if (interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException("Heartbeat interval must be positive");
    }
    this.interval = interval;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "sandbox-sse-heartbeat");
      thread.setDaemon(true);
      return thread;
    });
  }

  public SandboxRunDelivery wrap(SandboxRunDelivery delegate) {
    HeartbeatDelivery delivery = new HeartbeatDelivery(delegate);
    delivery.start();
    return delivery;
  }

  @Override
  @PreDestroy
  public void close() {
    scheduler.shutdownNow();
  }

  private final class HeartbeatDelivery implements SandboxRunDelivery {
    private final SandboxRunDelivery delegate;
    private final AtomicBoolean completed = new AtomicBoolean();
    private ScheduledFuture<?> heartbeat;

    private HeartbeatDelivery(SandboxRunDelivery delegate) {
      this.delegate = delegate;
    }

    private void start() {
      heartbeat = scheduler.scheduleAtFixedRate(
          this::emitHeartbeat,
          interval.toMillis(),
          interval.toMillis(),
          TimeUnit.MILLISECONDS);
    }

    @Override
    public void session(long sessionId) {
      delegate.session(sessionId);
    }

    @Override
    public void log(String line) {
      delegate.log(line);
    }

    @Override
    public void result(SandboxTerminalEvent event) {
      delegate.result(event);
    }

    @Override
    public void heartbeat() {
      delegate.heartbeat();
    }

    @Override
    public void complete() {
      if (completed.compareAndSet(false, true)) {
        heartbeat.cancel(false);
        delegate.complete();
      }
    }

    private void emitHeartbeat() {
      if (!completed.get()) {
        try {
          delegate.heartbeat();
        } catch (RuntimeException ignored) {
          // Heartbeat delivery is non-authoritative just like all other SSE writes.
        }
      }
    }
  }
}
