package ai.devpath.sandbox.run;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Orchestrates accepted runs without letting SSE delivery control execution persistence. */
@Service
public class SandboxRunService {

  private final SandboxRunPersistenceService persistence;
  private final RunnerBackend runnerBackend;
  private final SandboxRunExecutor executor;
  private final SandboxTerminalFinalizer terminalFinalizer;
  private final SandboxRunnerHealthIndicator runnerHealth;

  @Autowired
  public SandboxRunService(
      SandboxRunPersistenceService persistence,
      RunnerBackend runnerBackend,
      SandboxRunExecutor executor,
      SandboxTerminalFinalizer terminalFinalizer,
      SandboxRunnerHealthIndicator runnerHealth) {
    this.persistence = persistence;
    this.runnerBackend = runnerBackend;
    this.executor = executor;
    this.terminalFinalizer = terminalFinalizer;
    this.runnerHealth = runnerHealth;
  }

  SandboxRunService(
      SandboxRunPersistenceService persistence,
      RunnerBackend runnerBackend,
      SandboxRunExecutor executor,
      SandboxTerminalFinalizer terminalFinalizer) {
    this.persistence = persistence;
    this.runnerBackend = runnerBackend;
    this.executor = executor;
    this.terminalFinalizer = terminalFinalizer;
    this.runnerHealth = null;
  }

  public boolean isRunnerAvailable() {
    return runnerHealth == null ? runnerBackend.isAvailable() : runnerHealth.isAvailable();
  }

  public void assertCanAdmit(long userId) {
    executor.assertCanAdmit(userId);
    terminalFinalizer.assertCanReserve();
  }

  /** Returns only after ALLOCATING is committed and the execution task is admitted. */
  public AcceptedSandboxRun start(
      long userId,
      SandboxRunRequest request,
      SandboxRunDelivery delivery) {
    AtomicReference<AcceptedSandboxRun> accepted = new AtomicReference<>();
    executor.submit(userId, () -> {
      SandboxTerminalFinalizer.Reservation reservation = terminalFinalizer.reserve();
      try {
        SandboxSession session = persistence.allocate(userId, request);
        long sessionId = session.getId();
        accepted.set(new AcceptedSandboxRun(sessionId));
        deliver(() -> delivery.session(sessionId));
        return new AcceptedRunWork(sessionId, request, delivery, reservation);
      } catch (RuntimeException | Error failure) {
        reservation.close();
        throw failure;
      }
    });
    return accepted.get();
  }

  private void executeAccepted(
      long sessionId,
      SandboxRunRequest request,
      SandboxRunDelivery delivery,
      SandboxTerminalFinalizer.Reservation reservation) {
    try {
      if (!persistence.markRunning(sessionId)) {
        reservation.close();
        deliver(delivery::complete);
        return;
      }
    } catch (RuntimeException persistenceFailure) {
      finalizeResult(
          sessionId,
          killedResult(),
          delivery,
          reservation);
      return;
    }

    RunResult result;
    try {
      result = runnerBackend.run(
          new RunSpec(request.code(), request.language(), sessionId),
          line -> deliver(() -> delivery.log(line)),
          containerId -> {
            if (!persistence.attachContainer(sessionId, containerId)) {
              throw new SandboxUnavailableException("Sandbox session is already terminal");
            }
          });
      if (result == null) {
        throw new SandboxUnavailableException("Sandbox runner returned no result");
      }
    } catch (SandboxRunnerExecutionException runnerFailure) {
      result = runnerFailure.result();
    } catch (RuntimeException runnerFailure) {
      SandboxTerminalStatus status = Thread.currentThread().isInterrupted()
          ? SandboxTerminalStatus.KILLED
          : SandboxTerminalStatus.FAILED;
      int exitCode = status == SandboxTerminalStatus.KILLED ? -1 : 1;
      result = new RunResult(status, exitCode, "", "", null, null, false);
    }

    finalizeResult(sessionId, result, delivery, reservation);
  }

  private void finalizeResult(
      long sessionId,
      RunResult result,
      SandboxRunDelivery delivery,
      SandboxTerminalFinalizer.Reservation reservation) {
    try {
      SandboxSession terminal = terminalFinalizer.finish(
          sessionId, SandboxOutputLimits.limit(result), reservation);
      executor.recordTerminal(terminal);
      deliver(() -> delivery.result(SandboxTerminalEvent.from(terminal)));
    } catch (RuntimeException persistenceFailure) {
      // SandboxTerminalFinalizer retains the exact RunResult for background retry.
    } finally {
      deliver(delivery::complete);
    }
  }

  private static RunResult killedResult() {
    return new RunResult(
        SandboxTerminalStatus.KILLED, -1, "", "", null, null, false);
  }

  private static void deliver(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ignored) {
      // Delivery is best-effort and cannot cancel or relabel an accepted execution.
    }
  }

  private final class AcceptedRunWork implements SandboxRunExecutor.CancelableWork {
    private final long sessionId;
    private final SandboxRunRequest request;
    private final SandboxRunDelivery delivery;
    private final SandboxTerminalFinalizer.Reservation reservation;
    private final java.util.concurrent.atomic.AtomicBoolean claimed =
        new java.util.concurrent.atomic.AtomicBoolean();

    private AcceptedRunWork(
        long sessionId,
        SandboxRunRequest request,
        SandboxRunDelivery delivery,
        SandboxTerminalFinalizer.Reservation reservation) {
      this.sessionId = sessionId;
      this.request = request;
      this.delivery = delivery;
      this.reservation = reservation;
    }

    @Override
    public void run() {
      if (claimed.compareAndSet(false, true)) {
        executeAccepted(sessionId, request, delivery, reservation);
      }
    }

    @Override
    public void cancelBeforeStart() {
      if (claimed.compareAndSet(false, true)) {
        finalizeResult(sessionId, killedResult(), delivery, reservation);
      }
    }

    @Override
    public void cancelRunning() {
      runnerBackend.cancel(sessionId);
    }
  }
}
