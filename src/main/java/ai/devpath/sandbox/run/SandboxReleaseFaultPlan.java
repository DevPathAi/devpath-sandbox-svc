package ai.devpath.sandbox.run;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot, run-bound staging fault plan. The disabled singleton is inert. */
public final class SandboxReleaseFaultPlan {
  enum Fault {
    IMMEDIATE_DISCONNECT,
    MIDSTREAM_DISCONNECT,
    TIMEOUT,
    TRUNCATED
  }

  static final SandboxReleaseFaultPlan NONE = new SandboxReleaseFaultPlan(
      EnumSet.noneOf(Fault.class), null);

  private final EnumSet<Fault> faults;
  private final SandboxReleaseFaultRegistry.Observation observation;

  SandboxReleaseFaultPlan(
      EnumSet<Fault> faults,
      SandboxReleaseFaultRegistry.Observation observation) {
    this.faults = faults.clone();
    this.observation = observation;
  }

  public boolean active() {
    return !faults.isEmpty();
  }

  public SandboxRunDelivery wrap(SandboxRunDelivery delegate) {
    if (!active()) return delegate;
    AtomicBoolean disconnected = new AtomicBoolean();
    return new SandboxRunDelivery() {
      @Override
      public void session(long sessionId) {
        observation.recordSession(sessionId);
        delegate.session(sessionId);
        if (faults.contains(Fault.IMMEDIATE_DISCONNECT)
            && disconnected.compareAndSet(false, true)) {
          delegate.complete();
        }
      }

      @Override
      public void log(String line) {
        if (disconnected.get()) return;
        delegate.log(line);
        if (faults.contains(Fault.MIDSTREAM_DISCONNECT)
            && disconnected.compareAndSet(false, true)) {
          delegate.complete();
        }
      }

      @Override
      public void result(SandboxTerminalEvent event) {
        if (faults.contains(Fault.MIDSTREAM_DISCONNECT)
            && disconnected.compareAndSet(false, true)) {
          delegate.complete();
          return;
        }
        if (!disconnected.get()) delegate.result(event);
      }

      @Override
      public void complete() {
        if (disconnected.compareAndSet(false, true)) delegate.complete();
      }

      @Override
      public void heartbeat() {
        if (!disconnected.get()) delegate.heartbeat();
      }
    };
  }

  public RunResult apply(RunResult result) {
    if (!active()) return result;
    boolean truncated = result.outputTruncated() || faults.contains(Fault.TRUNCATED);
    if (faults.contains(Fault.TIMEOUT)) {
      return new RunResult(
          SandboxTerminalStatus.TIMED_OUT,
          -1,
          result.stdout(),
          result.stderr(),
          result.cpuMsUsed(),
          result.memoryMbPeak(),
          truncated);
    }
    if (truncated == result.outputTruncated()) return result;
    return new RunResult(
        result.terminalStatus(),
        result.exitCode(),
        result.stdout(),
        result.stderr(),
        result.cpuMsUsed(),
        result.memoryMbPeak(),
        true);
  }

  public void recordTerminal(SandboxTerminalEvent event) {
    if (observation != null) observation.recordTerminal(event);
  }
}
