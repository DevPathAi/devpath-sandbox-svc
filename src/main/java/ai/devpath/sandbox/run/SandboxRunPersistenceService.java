package ai.devpath.sandbox.run;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
  private final String ownerInstance;
  private final long leaseDurationMs;
  private final long reconciliationGraceMs;
  private final long reconciliationPublishGraceMs;

  public SandboxRunPersistenceService(
      SandboxSessionRepository sessions,
      SandboxRunEventPublisher eventPublisher,
      EntityManager entityManager,
      SandboxInstanceIdentity instanceIdentity,
      @Value("${devpath.sandbox.lease.duration-ms:20000}") long leaseDurationMs,
      @Value("${devpath.sandbox.reconcile.correction-grace-ms:30000}")
      long reconciliationGraceMs,
      @Value("${devpath.sandbox.reconcile.publish-grace-ms:30000}")
      long reconciliationPublishGraceMs) {
    String ownerInstance = instanceIdentity.value();
    if (ownerInstance == null || ownerInstance.isBlank()) {
      throw new IllegalArgumentException("Sandbox instance id must not be blank");
    }
    if (leaseDurationMs < 1) {
      throw new IllegalArgumentException("Sandbox lease duration must be positive");
    }
    if (reconciliationGraceMs < 1 || reconciliationPublishGraceMs < 1) {
      throw new IllegalArgumentException("Sandbox reconciliation grace must be positive");
    }
    this.sessions = sessions;
    this.eventPublisher = eventPublisher;
    this.entityManager = entityManager;
    this.ownerInstance = ownerInstance;
    this.leaseDurationMs = leaseDurationMs;
    this.reconciliationGraceMs = reconciliationGraceMs;
    this.reconciliationPublishGraceMs = reconciliationPublishGraceMs;
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
    Instant now = Instant.now();
    session.setStartedAt(now);
    session.setOwnerInstance(ownerInstance);
    session.setLeaseExpiresAt(now.plusMillis(leaseDurationMs));
    try {
      return sessions.saveAndFlush(session);
    } catch (RuntimeException failure) {
      if (SandboxPersistenceFailureClassifier.isUniqueViolation(failure)) {
        throw new SandboxBusyException("A Sandbox run is already active");
      }
      throw failure;
    }
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
    session.setLeaseExpiresAt(Instant.now().plusMillis(leaseDurationMs));
    sessions.save(session);
    return true;
  }

  /** The remote runner id must commit before the container is allowed to start. */
  @Transactional
  public boolean attachContainer(long sessionId, String containerId) {
    SandboxSession session = sessions.findByIdForUpdate(sessionId)
        .orElseThrow(() -> new SessionNotFoundException("sandbox session not found"));
    if (TERMINAL_STATUSES.contains(session.getStatus())) {
      return false;
    }
    session.setContainerId(containerId);
    session.setLeaseExpiresAt(Instant.now().plusMillis(leaseDurationMs));
    sessions.save(session);
    return true;
  }

  /** Renews queued and running rows owned by this process, independently of SSE delivery. */
  @Transactional
  public int renewOwnedLeases() {
    return renewOwnedLeases(ownerInstance);
  }

  /** Testable exact-owner entry point; a new process on the same pod cannot renew old rows. */
  @Transactional
  int renewOwnedLeases(String exactOwnerInstance) {
    return sessions.renewOwnedLeases(
        exactOwnerInstance, Instant.now().plusMillis(leaseDurationMs));
  }

  /** Persist terminal state and its Review outbox entry in one transaction. */
  @Transactional
  public SandboxSession finish(long sessionId, RunResult rawResult) {
    SandboxSession session = sessions.findByIdForUpdate(sessionId)
        .orElseThrow(() -> new SessionNotFoundException("sandbox session not found"));
    if (TERMINAL_STATUSES.contains(session.getStatus()) && !canCorrect(session)) {
      publishTerminal(session);
      return session;
    }

    SandboxSession saved = applyTerminal(session, rawResult);
    publishTerminal(saved);
    return saved;
  }

  /** Accurate terminal fallback used only after bounded atomic outbox retries fail. */
  @Transactional
  public SandboxSession finishWithoutEvent(long sessionId, RunResult rawResult) {
    SandboxSession session = sessions.findByIdForUpdate(sessionId)
        .orElseThrow(() -> new SessionNotFoundException("sandbox session not found"));
    if (TERMINAL_STATUSES.contains(session.getStatus()) && !canCorrect(session)) {
      return session;
    }
    return applyTerminal(session, rawResult);
  }

  /** Repairs terminal-only fallbacks. The deterministic outbox key makes races harmless. */
  @Transactional
  public int repairMissingTerminalEvents(int batchSize) {
    return repairMissingTerminalEvents(
        batchSize, Instant.now().minusMillis(reconciliationPublishGraceMs));
  }

  @Transactional
  int repairMissingTerminalEvents(int batchSize, Instant reconciliationPublishCutoff) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("Outbox repair batch size must be positive");
    }
    int inserted = 0;
    for (SandboxSession session : sessions.findTerminalWithoutOutbox(
        reconciliationPublishCutoff, batchSize)) {
      if (eventPublisher.publishSubmitted(
          session.getId(), session.getUserId(), session.getLanguage(), session.getContentId())) {
        inserted++;
      }
    }
    return inserted;
  }

  /** Reconcile accepted rows left non-terminal by process loss. */
  @Transactional
  public int reconcileExpired(Instant now, Instant legacyCutoff, int batchSize) {
    return reconcileExpiredAs(ownerInstance, now, legacyCutoff, batchSize);
  }

  @Transactional
  int reconcileExpiredAs(
      String reconcilerInstance,
      Instant now,
      Instant legacyCutoff,
      int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("Reconciliation batch size must be positive");
    }
    int reconciled = 0;
    for (SandboxSession session : sessions.findExpiredForReconciliation(
        now, legacyCutoff, reconcilerInstance, batchSize)) {
      boolean expired = session.getLeaseExpiresAt() == null
          ? session.getUpdatedAt().isBefore(legacyCutoff)
          : !session.getLeaseExpiresAt().isAfter(now);
      if (TERMINAL_STATUSES.contains(session.getStatus()) || !expired) {
        continue;
      }
      Instant reconciliationStartedAt = session.getReconciliationStartedAt();
      if (reconciliationStartedAt == null) {
        session.setReconciliationToken(java.util.UUID.randomUUID());
        session.setReconciliationStartedAt(now);
        // Give legacy rows a lease-based path through the second reconciliation phase.
        session.setLeaseExpiresAt(now);
        sessions.save(session);
        continue;
      }
      if (reconciliationStartedAt.isAfter(now.minusMillis(reconciliationGraceMs))) {
        continue;
      }
      SandboxTerminalStatus terminal = "ALLOCATING".equals(session.getStatus())
          ? SandboxTerminalStatus.FAILED
          : SandboxTerminalStatus.KILLED;
      session.setStatus(terminal.name());
      session.setFinishedAt(now);
      session.setExitCode(terminal == SandboxTerminalStatus.FAILED ? 1 : -1);
      session.setLeaseExpiresAt(null);
      session.setTerminalSource("RECONCILER");
      // Start a second grace before outbox publication so a surviving exact owner can correct.
      session.setReconciliationStartedAt(now);
      sessions.save(session);
      reconciled++;
    }
    return reconciled;
  }

  /** Compatibility entry point for callers rolling with the prior application version. */
  @Transactional
  public int reconcileStale(Instant cutoff) {
    return reconcileExpired(Instant.now(), cutoff, 100);
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

  private SandboxSession applyTerminal(SandboxSession session, RunResult rawResult) {
    RunResult result = SandboxOutputLimits.limit(rawResult);
    session.setFinishedAt(Instant.now());
    session.setExitCode(result.exitCode());
    session.setStdout(result.stdout());
    session.setStderr(result.stderr());
    session.setCpuMsUsed(result.cpuMsUsed());
    session.setMemoryMbPeak(result.memoryMbPeak());
    session.setOutputTruncated(result.outputTruncated());
    session.setStatus(result.terminalStatus().name());
    session.setLeaseExpiresAt(null);
    session.setTerminalSource("RUNNER");
    session.setReconciliationToken(null);
    session.setReconciliationStartedAt(null);
    return sessions.save(session);
  }

  private boolean canCorrect(SandboxSession session) {
    return "RECONCILER".equals(session.getTerminalSource())
        && ownerInstance.equals(session.getOwnerInstance());
  }
}
