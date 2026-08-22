package ai.devpath.sandbox.run;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Dedicated bounded runner pool with global and one-active-run-per-user admission. */
@Component
public class SandboxRunExecutor implements SmartLifecycle {

  interface CancelableWork extends Runnable {
    void cancelBeforeStart();

    default void cancelRunning() {}
  }

  private final ThreadPoolExecutor executor;
  private final Semaphore capacity;
  private final Set<Long> activeUsers = ConcurrentHashMap.newKeySet();
  private final Set<WorkItem> runningItems = ConcurrentHashMap.newKeySet();
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
  private final Counter userRejections;
  private final Counter capacityRejections;
  private final Counter drainingRejections;
  private final Counter truncatedRuns;
  private final Map<SandboxTerminalStatus, Counter> terminalRuns;
  private final long drainTimeoutMs;
  private final long activeCutoffMs;
  private final AtomicBoolean running = new AtomicBoolean(true);

  @Autowired
  public SandboxRunExecutor(
      @Value("${devpath.sandbox.executor.parallelism:4}") int parallelism,
      @Value("${devpath.sandbox.executor.queue-capacity:4}") int queueCapacity,
      @Value("${devpath.sandbox.executor.drain-timeout-ms:75000}") long drainTimeoutMs,
      @Value("${devpath.sandbox.executor.active-cutoff-ms:60000}") long activeCutoffMs,
      MeterRegistry registry) {
    if (parallelism < 1 || queueCapacity < 0 || drainTimeoutMs < 1
        || activeCutoffMs < 1 || activeCutoffMs >= drainTimeoutMs) {
      throw new IllegalArgumentException("Sandbox executor capacity must be positive");
    }
    this.drainTimeoutMs = drainTimeoutMs;
    this.activeCutoffMs = activeCutoffMs;
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

  SandboxRunExecutor(
      int parallelism,
      int queueCapacity,
      long drainTimeoutMs,
      MeterRegistry registry) {
    this(
        parallelism,
        queueCapacity,
        drainTimeoutMs,
        Math.max(1L, drainTimeoutMs * 4L / 5L),
        registry);
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

  /** Cheap, non-reserving request-path check; submit remains the authoritative admission. */
  public void assertCanAdmit(long userId) {
    lifecycleLock.readLock().lock();
    try {
      if (!running.get()) {
        drainingRejections.increment();
        throw new SandboxUnavailableException("Sandbox is draining");
      }
      if (activeUsers.contains(userId)) {
        userRejections.increment();
        throw new SandboxBusyException("A Sandbox run is already active");
      }
      if (capacity.availablePermits() == 0) {
        capacityRejections.increment();
        throw new SandboxBusyException("Sandbox capacity is full");
      }
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
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    long configuredCutoffNanos = TimeUnit.MILLISECONDS.toNanos(activeCutoffMs);
    long proportionalCutoffNanos = timeout.toNanos() * 4L / 5L;
    long activeCutoffNanos = System.nanoTime()
        + Math.max(1L, Math.min(configuredCutoffNanos, proportionalCutoffNanos));
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
      List<Runnable> queued = new ArrayList<>();
      executor.getQueue().drainTo(queued);
      cancelQueuedInParallel(queued, deadlineNanos);

      long activeWaitMs = remainingMillis(activeCutoffNanos);
      if (!executor.awaitTermination(activeWaitMs, TimeUnit.MILLISECONDS)) {
        cancelRunningWork();
        long remoteCancelWaitMs = Math.min(
            5_000L,
            Math.max(1L, remainingMillis(deadlineNanos) / 2L));
        if (executor.awaitTermination(remoteCancelWaitMs, TimeUnit.MILLISECONDS)) {
          return;
        }
        List<Runnable> neverStarted = executor.shutdownNow();
        cancelQueuedInParallel(neverStarted, deadlineNanos);
        long finalWaitMs = remainingMillis(deadlineNanos);
        if (finalWaitMs > 0) {
          executor.awaitTermination(finalWaitMs, TimeUnit.MILLISECONDS);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      List<Runnable> neverStarted = executor.shutdownNow();
      cancelQueuedInParallel(neverStarted, deadlineNanos);
    }
  }

  private void cancelRunningWork() {
    for (WorkItem workItem : Set.copyOf(runningItems)) {
      workItem.cancelRunning();
    }
  }

  private static void cancelQueuedInParallel(List<Runnable> queued, long deadlineNanos) {
    if (queued.isEmpty()) {
      return;
    }
    java.util.concurrent.CountDownLatch finished =
        new java.util.concurrent.CountDownLatch(queued.size());
    for (Runnable task : queued) {
      Thread.ofVirtual().name("sandbox-queued-cancel").start(() -> {
        try {
          if (task instanceof SandboxRunExecutor.WorkItem workItem) {
            workItem.cancelBeforeStart();
          }
        } finally {
          finished.countDown();
        }
      });
    }
    try {
      finished.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static long remainingMillis(long deadlineNanos) {
    long remainingNanos = deadlineNanos - System.nanoTime();
    if (remainingNanos <= 0) {
      return 0L;
    }
    return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
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
      if (!running.get()) {
        cancelBeforeStart();
        return;
      }
      runningItems.add(this);
      try {
        delegate.run();
      } finally {
        runningItems.remove(this);
        releaseOnce();
      }
    }

    private void cancelBeforeStart() {
      try {
        if (delegate instanceof CancelableWork cancelable) {
          cancelable.cancelBeforeStart();
        }
      } finally {
        releaseOnce();
      }
    }

    private void cancelRunning() {
      if (delegate instanceof CancelableWork cancelable) {
        cancelable.cancelRunning();
      }
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
