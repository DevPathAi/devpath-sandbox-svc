package ai.devpath.sandbox.run;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/** Orchestrates accepted runs without letting SSE delivery control execution persistence. */
@Service
public class SandboxRunService {

  private final SandboxRunPersistenceService persistence;
  private final RunnerBackend runnerBackend;
  private final SandboxRunExecutor executor;

  public SandboxRunService(
      SandboxRunPersistenceService persistence,
      RunnerBackend runnerBackend,
      SandboxRunExecutor executor) {
    this.persistence = persistence;
    this.runnerBackend = runnerBackend;
    this.executor = executor;
  }

  public boolean isRunnerAvailable() {
    return runnerBackend.isAvailable();
  }

  /** Returns only after ALLOCATING is committed and the execution task is admitted. */
  public AcceptedSandboxRun start(
      long userId,
      SandboxRunRequest request,
      SandboxRunDelivery delivery) {
    AtomicReference<AcceptedSandboxRun> accepted = new AtomicReference<>();
    executor.submit(userId, () -> {
      SandboxSession session = persistence.allocate(userId, request);
      long sessionId = session.getId();
      accepted.set(new AcceptedSandboxRun(sessionId));
      deliver(() -> delivery.session(sessionId));
      return () -> executeAccepted(sessionId, request, delivery);
    });
    return accepted.get();
  }

  private void executeAccepted(
      long sessionId,
      SandboxRunRequest request,
      SandboxRunDelivery delivery) {
    try {
      if (!persistence.markRunning(sessionId)) {
        deliver(delivery::complete);
        return;
      }
    } catch (RuntimeException persistenceFailure) {
      deliver(delivery::complete);
      return;
    }

    RunResult result;
    try {
      result = runnerBackend.run(
          new RunSpec(request.code(), request.language(), sessionId),
          line -> deliver(() -> delivery.log(line)));
    } catch (RuntimeException runnerFailure) {
      SandboxTerminalStatus status = Thread.currentThread().isInterrupted()
          ? SandboxTerminalStatus.KILLED
          : SandboxTerminalStatus.FAILED;
      int exitCode = status == SandboxTerminalStatus.KILLED ? -1 : 1;
      result = new RunResult(status, exitCode, "", "", null, null, false);
    }

    try {
      SandboxSession terminal = persistence.finish(sessionId, SandboxOutputLimits.limit(result));
      executor.recordTerminal(terminal);
      deliver(() -> delivery.result(SandboxTerminalEvent.from(terminal)));
    } catch (RuntimeException persistenceFailure) {
      // Reconciliation owns a RUNNING row whose terminal transaction could not commit.
    } finally {
      deliver(delivery::complete);
    }
  }

  private static void deliver(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ignored) {
      // Delivery is best-effort and cannot cancel or relabel an accepted execution.
    }
  }
}
