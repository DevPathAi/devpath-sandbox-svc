package ai.devpath.sandbox.run;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Dedicated bounded runner pool with global and one-active-run-per-user admission. */
@Component
public class SandboxRunExecutor implements SmartLifecycle {

  private final ThreadPoolExecutor executor;
  private final Semaphore capacity;
  private final Set<Long> activeUsers = ConcurrentHashMap.newKeySet();
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
  private final Counter userRejections;
  private final Counter capacityRejections;
  private final Counter drainingRejections;
  private final Counter truncatedRuns;
  private final Map<SandboxTerminalStatus, Counter> terminalRuns;
  private final long drainTimeoutMs;
  private final AtomicBoolean running = new AtomicBoolean(true);

  public SandboxRunExecutor(
      @Value("${devpath.sandbox.executor.parallelism:4}") int parallelism,
      @Value("${devpath.sandbox.executor.queue-capacity:4}") int queueCapacity,
      @Value("${devpath.sandbox.executor.drain-timeout-ms:75000}") long drainTimeoutMs,
      MeterRegistry registry) {
    if (parallelism < 1 || queueCapacity < 0) {
      throw new IllegalArgumentException("Sandbox executor capacity must be positive");
    }
    this.drainTimeoutMs = drainTimeoutMs;
    BlockingQueue<Runnable> queue = queueCapacity == 0
        ? new SynchronousQueue<>()
        : new ArrayBlockingQueue<>(queueCapacity);
    this.executor = new ThreadPoolExecutor(
        parallelism,
        parallelism,
        0L,
        TimeUnit.MILLISECONDS,
        queue,
        new SandboxThreadFactory(),
        new ThreadPoolExecutor.AbortPolicy());
    this.capacity = new Semaphore(parallelism + queueCapacity);
    this.userRejections = Counter.builder("sandbox.runs.rejected")
        .tag("reason", "user_active").register(registry);
    this.capacityRejections = Counter.builder("sandbox.runs.rejected")
        .tag("reason", "capacity").register(registry);
    this.drainingRejections = Counter.builder("sandbox.runs.rejected")
        .tag("reason", "draining").register(registry);
    this.truncatedRuns = Counter.builder("sandbox.runs.truncated").register(registry);
    this.terminalRuns = new EnumMap<>(SandboxTerminalStatus.class);
    for (SandboxTerminalStatus status : SandboxTerminalStatus.values()) {
      terminalRuns.put(status, Counter.builder("sandbox.runs.terminal")
          .tag("status", status.name()).register(registry));
    }
    Gauge.builder("sandbox.runs.active", executor, ThreadPoolExecutor::getActiveCount)
        .register(registry);
    Gauge.builder("sandbox.runs.queued", executor, value -> value.getQueue().size())
        .register(registry);
  }

  /**
   * Reserves admission, builds the accepted work synchronously, and submits it atomically against
   * lifecycle drain. The supplier is where the caller durably creates the ALLOCATING row.
   */
  public void submit(long userId, Supplier<Runnable> acceptedWork) {
    lifecycleLock.readLock().lock();
    boolean userReserved = false;
    boolean capacityReserved = false;
    try {
      if (!running.get()) {
        drainingRejections.increment();
        throw new SandboxUnavailableException("Sandbox is draining");
      }
      userReserved = activeUsers.add(userId);
      if (!userReserved) {
        userRejections.increment();
        throw new SandboxBusyException("A Sandbox run is already active");
      }
      capacityReserved = capacity.tryAcquire();
      if (!capacityReserved) {
        capacityRejections.increment();
        throw new SandboxBusyException("Sandbox capacity is full");
      }

      Runnable delegate = acceptedWork.get();
      executor.execute(new WorkItem(userId, delegate));
    } catch (RejectedExecutionException e) {
      release(userId);
      throw new SandboxUnavailableException("Sandbox is draining", e);
    } catch (RuntimeException | Error e) {
      if (capacityReserved) {
        capacity.release();
      }
      if (userReserved) {
        activeUsers.remove(userId);
      }
      throw e;
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public void start() {
    // The executor is ready immediately after construction and is intentionally not restartable.
  }

  @Override
  public void stop() {
    drain(Duration.ofMillis(drainTimeoutMs));
  }

  @Override
  public void stop(Runnable callback) {
    Thread thread = new Thread(() -> {
      try {
        stop();
      } finally {
        callback.run();
      }
    }, "sandbox-run-drain");
    thread.setDaemon(true);
    thread.start();
  }

  void stopForTest(Duration timeout) {
    drain(timeout);
  }

  void recordTerminal(SandboxSession terminal) {
    SandboxTerminalStatus status = SandboxTerminalStatus.valueOf(terminal.getStatus());
    terminalRuns.get(status).increment();
    if (terminal.isOutputTruncated()) {
      truncatedRuns.increment();
    }
  }

  private void drain(Duration timeout) {
    lifecycleLock.writeLock().lock();
    try {
      if (!running.getAndSet(false)) {
        return;
      }
      executor.shutdown();
    } finally {
      lifecycleLock.writeLock().unlock();
    }

    try {
      if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        List<Runnable> neverStarted = executor.shutdownNow();
        neverStarted.forEach(task -> {
          if (task instanceof WorkItem workItem) {
            workItem.cancelBeforeStart();
          }
        });
        executor.awaitTermination(Math.min(timeout.toMillis(), 5_000L), TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      List<Runnable> neverStarted = executor.shutdownNow();
      neverStarted.forEach(task -> {
        if (task instanceof WorkItem workItem) {
          workItem.cancelBeforeStart();
        }
      });
    }
  }

  private void release(long userId) {
    activeUsers.remove(userId);
    capacity.release();
  }

  private final class WorkItem implements Runnable {
    private final long userId;
    private final Runnable delegate;
    private final AtomicBoolean released = new AtomicBoolean();

    private WorkItem(long userId, Runnable delegate) {
      this.userId = userId;
      this.delegate = delegate;
    }

    @Override
    public void run() {
      try {
        delegate.run();
      } finally {
        releaseOnce();
      }
    }

    private void cancelBeforeStart() {
      releaseOnce();
    }

    private void releaseOnce() {
      if (released.compareAndSet(false, true)) {
        release(userId);
      }
    }
  }

  private static final class SandboxThreadFactory implements ThreadFactory {
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public Thread newThread(Runnable task) {
      Thread thread = new Thread(task, "sandbox-run-" + sequence.incrementAndGet());
      thread.setDaemon(false);
      return thread;
    }
  }
}
