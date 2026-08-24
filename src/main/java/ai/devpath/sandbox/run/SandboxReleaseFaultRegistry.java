package ai.devpath.sandbox.run;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Staging-only, candidate/run-bound fault state. Production defaults to disabled. */
@Component
public class SandboxReleaseFaultRegistry {
  private static final Pattern CANDIDATE = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern RUN_KEY = Pattern.compile("^[A-Za-z0-9_-]{22,128}$");
  private static final int MAX_RUNS = 64;

  private final boolean enabled;
  private final Map<Key, Observation> runs = new ConcurrentHashMap<>();

  public SandboxReleaseFaultRegistry(
      @Value("${devpath.release.enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public void arm(String candidate, String runKey, String command) {
    requireEnabled();
    Key key = requireKey(candidate, runKey);
    SandboxReleaseFaultPlan.Fault fault = switch (command) {
      case "next-run-immediate-disconnect" ->
          SandboxReleaseFaultPlan.Fault.IMMEDIATE_DISCONNECT;
      case "next-run-midstream-disconnect" ->
          SandboxReleaseFaultPlan.Fault.MIDSTREAM_DISCONNECT;
      case "next-run-timeout" -> SandboxReleaseFaultPlan.Fault.TIMEOUT;
      case "next-run-truncated" -> SandboxReleaseFaultPlan.Fault.TRUNCATED;
      default -> throw new IllegalArgumentException("unsupported Sandbox release fault");
    };
    Observation observation = state(key);
    synchronized (observation) {
      observation.armed.add(fault);
    }
  }

  public SandboxReleaseFaultPlan consumeForRun(String candidate, String runKey) {
    if (!enabled || !valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY)) {
      return SandboxReleaseFaultPlan.NONE;
    }
    Observation observation = runs.get(new Key(candidate, runKey));
    if (observation == null) return SandboxReleaseFaultPlan.NONE;
    synchronized (observation) {
      if (observation.armed.isEmpty()) return SandboxReleaseFaultPlan.NONE;
      EnumSet<SandboxReleaseFaultPlan.Fault> faults = observation.armed.clone();
      observation.armed.clear();
      observation.activated.addAll(faults);
      observation.acceptedAt = Instant.now();
      return new SandboxReleaseFaultPlan(faults, observation);
    }
  }

  public void recordOwnerRecovery(
      String candidate,
      String runKey,
      long sessionId,
      boolean truncated) {
    if (!enabled || !valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY)) return;
    Observation observation = runs.get(new Key(candidate, runKey));
    if (observation == null || observation.sessionId != sessionId) return;
    observation.ownerRecovered = true;
    observation.recoveredTruncated = truncated;
  }

  public void recordStaleReconciliation(
      String candidate,
      String runKey,
      String originalStatus,
      boolean passed) {
    requireEnabled();
    Observation observation = state(requireKey(candidate, runKey));
    if ("ALLOCATING".equals(originalStatus)) {
      observation.staleAllocatingReconciled = passed;
    } else if ("RUNNING".equals(originalStatus)) {
      observation.staleRunningReconciled = passed;
    } else {
      throw new IllegalArgumentException("unsupported stale Sandbox status");
    }
  }

  public boolean checkpoint(String candidate, String runKey, String checkpoint) {
    if (!enabled || !valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY)) return false;
    Observation value = runs.get(new Key(candidate, runKey));
    if (value == null) return false;
    return switch (checkpoint) {
      case "session-id-within-one-second" -> value.sessionDeliveredAt != null
          && value.acceptedAt != null
          && Duration.between(value.acceptedAt, value.sessionDeliveredAt).compareTo(
              Duration.ofSeconds(1)) <= 0;
      case "immediate-disconnect-timed-out" ->
          value.activated.contains(SandboxReleaseFaultPlan.Fault.IMMEDIATE_DISCONNECT)
              && "TIMED_OUT".equals(value.terminalStatus);
      case "owner-recovery-timed-out" ->
          value.ownerRecovered && "TIMED_OUT".equals(value.terminalStatus);
      case "midstream-disconnect-completed" ->
          value.activated.contains(SandboxReleaseFaultPlan.Fault.MIDSTREAM_DISCONNECT)
              && "COMPLETED".equals(value.terminalStatus);
      case "owner-recovery-truncated" ->
          value.ownerRecovered && value.recoveredTruncated;
      case "stale-allocating-reconciled" -> value.staleAllocatingReconciled;
      case "stale-running-reconciled" -> value.staleRunningReconciled;
      default -> false;
    };
  }

  public void clear(String candidate, String runKey) {
    requireEnabled();
    runs.remove(requireKey(candidate, runKey));
  }

  private Observation state(Key key) {
    Observation existing = runs.get(key);
    if (existing != null) return existing;
    if (runs.size() >= MAX_RUNS) {
      throw new IllegalStateException("Sandbox release run capacity is exhausted");
    }
    return runs.computeIfAbsent(key, ignored -> new Observation());
  }

  private void requireEnabled() {
    if (!enabled) throw new IllegalStateException("Sandbox release hooks are disabled");
  }

  private static Key requireKey(String candidate, String runKey) {
    if (!valid(candidate, CANDIDATE) || !valid(runKey, RUN_KEY)) {
      throw new IllegalArgumentException("Sandbox release binding is invalid");
    }
    return new Key(candidate, runKey);
  }

  private static boolean valid(String value, Pattern pattern) {
    return value != null && pattern.matcher(value).matches();
  }

  private record Key(String candidate, String runKey) {}

  static final class Observation {
    private final EnumSet<SandboxReleaseFaultPlan.Fault> armed =
        EnumSet.noneOf(SandboxReleaseFaultPlan.Fault.class);
    private final EnumSet<SandboxReleaseFaultPlan.Fault> activated =
        EnumSet.noneOf(SandboxReleaseFaultPlan.Fault.class);
    private volatile Instant acceptedAt;
    private volatile Instant sessionDeliveredAt;
    private volatile Long sessionId;
    private volatile String terminalStatus;
    private volatile boolean ownerRecovered;
    private volatile boolean recoveredTruncated;
    private volatile boolean staleAllocatingReconciled;
    private volatile boolean staleRunningReconciled;

    void recordSession(long value) {
      sessionId = value;
      sessionDeliveredAt = Instant.now();
    }

    void recordTerminal(SandboxTerminalEvent value) {
      sessionId = value.sessionId();
      terminalStatus = value.status();
    }
  }
}
