package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxReleaseFaultRegistryTest {
  private static final String CANDIDATE = "a".repeat(64);
  private static final String RUN_KEY = "R".repeat(43);

  @Test
  void disabledOrUnboundRequestsNeverReceiveFaults() {
    SandboxReleaseFaultRegistry disabled = new SandboxReleaseFaultRegistry(false);
    assertThatThrownBy(() -> disabled.arm(
        CANDIDATE, RUN_KEY, "next-run-timeout"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(disabled.consumeForRun(CANDIDATE, RUN_KEY).active()).isFalse();

    SandboxReleaseFaultRegistry enabled = new SandboxReleaseFaultRegistry(true);
    enabled.arm(CANDIDATE, RUN_KEY, "next-run-timeout");
    assertThat(enabled.consumeForRun(null, null).active()).isFalse();
    assertThat(enabled.consumeForRun("bad", RUN_KEY).active()).isFalse();
  }

  @Test
  void immediateDisconnectAndTimeoutAreConsumedOnceAndObserved() {
    SandboxReleaseFaultRegistry registry = new SandboxReleaseFaultRegistry(true);
    registry.arm(CANDIDATE, RUN_KEY, "next-run-immediate-disconnect");
    registry.arm(CANDIDATE, RUN_KEY, "next-run-timeout");

    SandboxReleaseFaultPlan plan = registry.consumeForRun(CANDIDATE, RUN_KEY);
    RecordingDelivery delegate = new RecordingDelivery();
    SandboxRunDelivery delivery = plan.wrap(delegate);
    delivery.session(71L);

    assertThat(delegate.events).containsExactly("session:71", "complete");
    RunResult result = plan.apply(new RunResult(0, "ok", "", 1L, 2));
    assertThat(result.terminalStatus()).isEqualTo(SandboxTerminalStatus.TIMED_OUT);
    plan.recordTerminal(new SandboxTerminalEvent(71L, "TIMED_OUT", -1, false));
    assertThat(registry.checkpoint(
        CANDIDATE, RUN_KEY, "session-id-within-one-second")).isTrue();
    assertThat(registry.checkpoint(
        CANDIDATE, RUN_KEY, "immediate-disconnect-timed-out")).isTrue();
    assertThat(registry.consumeForRun(CANDIDATE, RUN_KEY).active()).isFalse();
  }

  @Test
  void midstreamDisconnectKeepsDurableTruncatedResultForOwnerRecovery() {
    SandboxReleaseFaultRegistry registry = new SandboxReleaseFaultRegistry(true);
    registry.arm(CANDIDATE, RUN_KEY, "next-run-midstream-disconnect");
    registry.arm(CANDIDATE, RUN_KEY, "next-run-truncated");

    SandboxReleaseFaultPlan plan = registry.consumeForRun(CANDIDATE, RUN_KEY);
    RecordingDelivery delegate = new RecordingDelivery();
    SandboxRunDelivery delivery = plan.wrap(delegate);
    delivery.session(72L);
    delivery.log("partial");
    delivery.log("hidden");
    RunResult result = plan.apply(new RunResult(0, "ok", "", 1L, 2));
    plan.recordTerminal(new SandboxTerminalEvent(
        72L, result.terminalStatus().name(), result.exitCode(), result.outputTruncated()));
    registry.recordOwnerRecovery(CANDIDATE, RUN_KEY, 72L, result.outputTruncated());

    assertThat(delegate.events).containsExactly(
        "session:72", "log:partial", "complete");
    assertThat(result.terminalStatus()).isEqualTo(SandboxTerminalStatus.COMPLETED);
    assertThat(result.outputTruncated()).isTrue();
    assertThat(registry.checkpoint(
        CANDIDATE, RUN_KEY, "midstream-disconnect-completed")).isTrue();
    assertThat(registry.checkpoint(
        CANDIDATE, RUN_KEY, "owner-recovery-truncated")).isTrue();
  }

  @Test
  void staleCheckpointIsRecordedOnlyForTheMatchingRun() {
    SandboxReleaseFaultRegistry registry = new SandboxReleaseFaultRegistry(true);
    registry.recordStaleReconciliation(CANDIDATE, RUN_KEY, "ALLOCATING", true);
    assertThat(registry.checkpoint(
        CANDIDATE, RUN_KEY, "stale-allocating-reconciled")).isTrue();
    assertThat(registry.checkpoint(
        "b".repeat(64), RUN_KEY, "stale-allocating-reconciled")).isFalse();
  }

  private static final class RecordingDelivery implements SandboxRunDelivery {
    private final List<String> events = new ArrayList<>();

    @Override public void session(long sessionId) { events.add("session:" + sessionId); }
    @Override public void log(String line) { events.add("log:" + line); }
    @Override public void result(SandboxTerminalEvent event) { events.add("result:" + event.status()); }
    @Override public void complete() { events.add("complete"); }
  }
}
