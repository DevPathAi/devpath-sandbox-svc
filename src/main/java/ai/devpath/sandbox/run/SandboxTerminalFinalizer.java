package ai.devpath.sandbox.run;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Persists exact terminal results with admission-time memory reservations. Accepted executions
 * always own enough bounded headroom to retain their maximum result through a database outage.
 */
@Component
public class SandboxTerminalFinalizer {

  static final int RESULT_RESERVATION_BYTES =
      SandboxOutputLimits.MAX_COMBINED_OUTPUT_BYTES + 1_024;

  private final SandboxRunPersistenceService persistence;
  private final int attempts;
  private final int maxEntries;
  private final long maxBytes;
  private final long retryBudgetNanos;
  private final int retryBatchSize;
  private final Counter atomicFailures;
  private final Counter fallbackFailures;
  private final Counter fallbacks;
  private final Counter deferred;
  private final ConcurrentMap<Long, PendingTerminal> pending = new ConcurrentHashMap<>();
  private final AtomicInteger reservedEntries = new AtomicInteger();
  private final AtomicLong reservedBytes = new AtomicLong();
  private final Object reservationLock = new Object();

  @Autowired
  public SandboxTerminalFinalizer(
      SandboxRunPersistenceService persistence,
      MeterRegistry metrics,
      @Value("${devpath.sandbox.terminal-persistence-attempts:3}") int attempts,
      @Value("${devpath.sandbox.terminal-backlog.max-entries:8}") int maxEntries,
      @Value("${devpath.sandbox.terminal-backlog.max-bytes:2105344}") long maxBytes,
      @Value("${devpath.sandbox.terminal-retry-budget-ms:4000}") long retryBudgetMs,
      @Value("${devpath.sandbox.terminal-retry-batch-size:2}") int retryBatchSize) {
    this(persistence, metrics, attempts, maxEntries, maxBytes,
        Duration.ofMillis(retryBudgetMs), retryBatchSize);
  }

  SandboxTerminalFinalizer(
      SandboxRunPersistenceService persistence,
      MeterRegistry metrics,
      int attempts) {
    this(persistence, metrics, attempts, 8, 8L * RESULT_RESERVATION_BYTES,
        Duration.ofSeconds(4), 2);
  }

  SandboxTerminalFinalizer(
      SandboxRunPersistenceService persistence,
      MeterRegistry metrics,
      int attempts,
      int maxEntries,
      long maxBytes,
      Duration retryBudget) {
    this(persistence, metrics, attempts, maxEntries, maxBytes, retryBudget, 2);
  }

  SandboxTerminalFinalizer(
      SandboxRunPersistenceService persistence,
      MeterRegistry metrics,
      int attempts,
      int maxEntries,
      long maxBytes,
      Duration retryBudget,
      int retryBatchSize) {
    if (attempts < 1) {
      throw new IllegalArgumentException("Terminal persistence attempts must be positive");
    }
    if (maxEntries < 1 || maxBytes < RESULT_RESERVATION_BYTES) {
      throw new IllegalArgumentException("Terminal backlog capacity must fit one exact result");
    }
    if (retryBudget == null || retryBudget.isZero() || retryBudget.isNegative()) {
      throw new IllegalArgumentException("Terminal retry budget must be positive");
    }
    if (retryBatchSize < 1) {
      throw new IllegalArgumentException("Terminal retry batch must be positive");
    }
    this.persistence = persistence;
    this.attempts = attempts;
    this.maxEntries = maxEntries;
    this.maxBytes = maxBytes;
    this.retryBudgetNanos = retryBudget.toNanos();
    this.retryBatchSize = Math.min(retryBatchSize, maxEntries);
    this.atomicFailures = Counter.builder("sandbox.runs.terminal_persistence_failures")
        .tag("stage", "atomic").register(metrics);
    this.fallbackFailures = Counter.builder("sandbox.runs.terminal_persistence_failures")
        .tag("stage", "fallback").register(metrics);
    this.fallbacks = Counter.builder("sandbox.runs.terminal_fallback").register(metrics);
    this.deferred = Counter.builder("sandbox.runs.terminal_deferred").register(metrics);
    Gauge.builder("sandbox.runs.terminal_pending", pending, ConcurrentMap::size)
        .register(metrics);
    Gauge.builder("sandbox.runs.terminal_reserved_entries", reservedEntries, AtomicInteger::get)
        .register(metrics);
    Gauge.builder("sandbox.runs.terminal_reserved_bytes", reservedBytes, AtomicLong::get)
        .register(metrics);
  }

  /** Reserves maximum exact-result headroom before the durable ALLOCATING row is accepted. */
  public Reservation reserve() {
    synchronized (reservationLock) {
      if (!hasCapacityLocked()) {
        throw new SandboxUnavailableException("Sandbox terminal result capacity is full");
      }
      reservedEntries.incrementAndGet();
      reservedBytes.addAndGet(RESULT_RESERVATION_BYTES);
      return new Reservation();
    }
  }

  public void assertCanReserve() {
    synchronized (reservationLock) {
      if (!hasCapacityLocked()) {
        throw new SandboxUnavailableException("Sandbox terminal result capacity is full");
      }
    }
  }

  public boolean hasCapacity() {
    synchronized (reservationLock) {
      return hasCapacityLocked();
    }
  }

  public SandboxSession finish(long sessionId, RunResult result) {
    Reservation reservation = reserve();
    try {
      return finish(sessionId, result, reservation);
    } catch (RuntimeException failure) {
      if (!pending.containsKey(sessionId)) {
        reservation.close();
      }
      throw failure;
    }
  }

  public SandboxSession finish(long sessionId, RunResult rawResult, Reservation reservation) {
    requireOpenReservation(reservation);
    PendingTerminal retained = pending.get(sessionId);
    if (retained != null && retained.reservation() != reservation) {
      // A compatibility replay must never replace an exact result already retained in memory.
      reservation.close();
      return persistRetained(sessionId, retained);
    }
    RunResult result = SandboxOutputLimits.limit(rawResult);
    long retryDeadline = System.nanoTime() + retryBudgetNanos;
    try {
      SandboxSession saved = persist(result, sessionId, retryDeadline);
      PendingTerminal removed = pending.remove(sessionId);
      if (removed != null && removed.reservation() != reservation) {
        removed.reservation().close();
      }
      reservation.close();
      return saved;
    } catch (RuntimeException failure) {
      PendingTerminal candidate = new PendingTerminal(result, reservation);
      PendingTerminal existing = pending.putIfAbsent(sessionId, candidate);
      if (existing != null && existing.reservation() != reservation) {
        reservation.close();
      }
      deferred.increment();
      throw failure;
    }
  }

  @Scheduled(
      initialDelayString = "${devpath.sandbox.terminal-retry-delay-ms:5000}",
      fixedDelayString = "${devpath.sandbox.terminal-retry-delay-ms:5000}")
  public int retryPending() {
    int finalized = 0;
    int inspected = 0;
    for (var entry : Map.copyOf(pending).entrySet()) {
      if (inspected++ >= retryBatchSize) {
        break;
      }
      PendingTerminal exact = entry.getValue();
      try {
        SandboxSession ignored = persist(
            exact.result(), entry.getKey(), System.nanoTime() + retryBudgetNanos);
        if (pending.remove(entry.getKey(), exact)) {
          exact.reservation().close();
          finalized++;
        }
      } catch (RuntimeException stillUnavailable) {
        // The bounded reservation and exact result remain for a later recovery probe.
      }
    }
    return finalized;
  }

  int pendingCount() {
    return pending.size();
  }

  private SandboxSession persistRetained(long sessionId, PendingTerminal retained) {
    SandboxSession saved = persist(
        retained.result(), sessionId, System.nanoTime() + retryBudgetNanos);
    if (pending.remove(sessionId, retained)) {
      retained.reservation().close();
    }
    return saved;
  }

  private SandboxSession persist(RunResult result, long sessionId, long retryDeadline) {
    RuntimeException atomicFailure;
    try {
      return retry(
          () -> persistence.finish(sessionId, result), atomicFailures, retryDeadline);
    } catch (RuntimeException failure) {
      atomicFailure = failure;
    }

    fallbacks.increment();
    try {
      return retry(
          () -> persistence.finishWithoutEvent(sessionId, result),
          fallbackFailures,
          retryDeadline);
    } catch (RuntimeException fallbackFailure) {
      fallbackFailure.addSuppressed(atomicFailure);
      throw fallbackFailure;
    }
  }

  private SandboxSession retry(
      Supplier<SandboxSession> operation,
      Counter failureCounter,
      long retryDeadline) {
    RuntimeException lastFailure = null;
    for (int attempt = 0; attempt < attempts; attempt++) {
      try {
        return operation.get();
      } catch (RuntimeException failure) {
        failureCounter.increment();
        lastFailure = failure;
        if (!SandboxPersistenceFailureClassifier.isTransient(failure)
            || System.nanoTime() >= retryDeadline) {
          break;
        }
      }
    }
    throw lastFailure == null
        ? new IllegalStateException("Terminal persistence failed without a cause")
        : lastFailure;
  }

  private boolean hasCapacityLocked() {
    return reservedEntries.get() < maxEntries
        && reservedBytes.get() + RESULT_RESERVATION_BYTES <= maxBytes;
  }

  private void requireOpenReservation(Reservation reservation) {
    if (reservation == null || reservation.owner != this || !reservation.open.get()) {
      throw new IllegalArgumentException("Terminal reservation is not open");
    }
  }

  private void release(Reservation reservation) {
    if (reservation.owner == this && reservation.open.compareAndSet(true, false)) {
      synchronized (reservationLock) {
        reservedEntries.decrementAndGet();
        reservedBytes.addAndGet(-RESULT_RESERVATION_BYTES);
      }
    }
  }

  private record PendingTerminal(RunResult result, Reservation reservation) {}

  public final class Reservation implements AutoCloseable {
    private final SandboxTerminalFinalizer owner = SandboxTerminalFinalizer.this;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private Reservation() {}

    @Override
    public void close() {
      release(this);
    }
  }
}
