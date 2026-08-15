package ai.devpath.sandbox.run;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions for the durable accepted-run lifecycle. */
@Service
public class SandboxRunPersistenceService {

  private static final List<String> ACTIVE_STATUSES = List.of("ALLOCATING", "RUNNING");
  private static final Set<String> TERMINAL_STATUSES = Set.of(
      "COMPLETED", "FAILED", "KILLED", "TIMED_OUT");

  private final SandboxSessionRepository sessions;
  private final SandboxRunEventPublisher eventPublisher;
  private final EntityManager entityManager;

  public SandboxRunPersistenceService(
      SandboxSessionRepository sessions,
      SandboxRunEventPublisher eventPublisher,
      EntityManager entityManager) {
    this.sessions = sessions;
    this.eventPublisher = eventPublisher;
    this.entityManager = entityManager;
  }

  /** Capacity has already been reserved; commit only ALLOCATING and its stable identifier. */
  @Transactional
  public SandboxSession allocate(long userId, SandboxRunRequest request) {
    acquireUserAdmissionLock(userId);
    if (sessions.existsByUserIdAndStatusIn(userId, ACTIVE_STATUSES)) {
      throw new SandboxBusyException("A Sandbox run is already active");
    }

    SandboxSession session = new SandboxSession();
    session.setUserId(userId);
    session.setLanguage(request.language());
    session.setSubmittedCode(request.code());
    session.setContentId(request.contentId());
    session.setCodeBlockId(request.codeBlockId());
    session.setStatus("ALLOCATING");
    session.setStartedAt(Instant.now());
    return sessions.saveAndFlush(session);
  }

  @Transactional
  public boolean markRunning(long sessionId) {
    SandboxSession session = sessions.findByIdForUpdate(sessionId)
        .orElseThrow(() -> new SessionNotFoundException("sandbox session not found"));
    if ("RUNNING".equals(session.getStatus())) {
      return true;
    }
    if (!"ALLOCATING".equals(session.getStatus())) {
      return false;
    }
    session.setStatus("RUNNING");
    sessions.save(session);
    return true;
  }

  /** Persist terminal state and its Review outbox entry in one transaction. */
  @Transactional
  public SandboxSession finish(long sessionId, RunResult rawResult) {
    SandboxSession session = sessions.findByIdForUpdate(sessionId)
        .orElseThrow(() -> new SessionNotFoundException("sandbox session not found"));
    if (TERMINAL_STATUSES.contains(session.getStatus())) {
      return session;
    }

    RunResult result = SandboxOutputLimits.limit(rawResult);
    session.setFinishedAt(Instant.now());
    session.setExitCode(result.exitCode());
    session.setStdout(result.stdout());
    session.setStderr(result.stderr());
    session.setCpuMsUsed(result.cpuMsUsed());
    session.setMemoryMbPeak(result.memoryMbPeak());
    session.setOutputTruncated(result.outputTruncated());
    session.setStatus(result.terminalStatus().name());
    SandboxSession saved = sessions.save(session);
    publishTerminal(saved);
    return saved;
  }

  /** Reconcile accepted rows left non-terminal by process loss. */
  @Transactional
  public int reconcileStale(Instant cutoff) {
    int reconciled = 0;
    for (Long sessionId : sessions.findStaleIds(ACTIVE_STATUSES, cutoff)) {
      SandboxSession session = sessions.findByIdForUpdate(sessionId).orElse(null);
      if (session == null || TERMINAL_STATUSES.contains(session.getStatus())
          || !session.getUpdatedAt().isBefore(cutoff)) {
        continue;
      }
      SandboxTerminalStatus terminal = "ALLOCATING".equals(session.getStatus())
          ? SandboxTerminalStatus.FAILED
          : SandboxTerminalStatus.KILLED;
      session.setStatus(terminal.name());
      session.setFinishedAt(Instant.now());
      session.setExitCode(terminal == SandboxTerminalStatus.FAILED ? 1 : -1);
      SandboxSession saved = sessions.save(session);
      publishTerminal(saved);
      reconciled++;
    }
    return reconciled;
  }

  private void acquireUserAdmissionLock(long userId) {
    Object acquired = entityManager.createNativeQuery("SELECT pg_try_advisory_xact_lock(:userId)")
        .setParameter("userId", userId)
        .getSingleResult();
    if (!Boolean.TRUE.equals(acquired)) {
      throw new SandboxBusyException("A Sandbox run is already active");
    }
  }

  private void publishTerminal(SandboxSession session) {
    eventPublisher.publishSubmitted(
        session.getId(), session.getUserId(), session.getLanguage(), session.getContentId());
  }
}
