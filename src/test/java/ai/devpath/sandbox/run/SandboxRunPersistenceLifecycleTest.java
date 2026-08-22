package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.devpath.sandbox.outbox.OutboxRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class SandboxRunPersistenceLifecycleTest {

  @Autowired SandboxRunPersistenceService persistence;
  @Autowired SandboxSessionRepository sessions;
  @Autowired OutboxRepository outbox;
  @Autowired DataSource dataSource;
  @Autowired SandboxRunEventPublisher eventPublisher;
  @Autowired SandboxInstanceIdentity instanceIdentity;
  @Autowired EntityManager entityManager;
  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void cleanup() {
    outbox.deleteAll();
    sessions.deleteAll();
  }

  @Test
  void allocationIsDurableBeforeRunningTransition() {
    SandboxSession allocated = persistence.allocate(
        501L, new SandboxRunRequest("print(1)", "PYTHON", 10L, 20L));

    assertThat(allocated.getId()).isNotNull();
    SandboxSession durable = sessions.findById(allocated.getId()).orElseThrow();
    assertThat(durable.getStatus()).isEqualTo("ALLOCATING");
    assertThat(durable.getOwnerInstance()).isNotBlank();
    assertThat(durable.getLeaseExpiresAt()).isAfter(Instant.now());

    persistence.markRunning(allocated.getId());

    assertThat(sessions.findById(allocated.getId()).orElseThrow().getStatus())
        .isEqualTo("RUNNING");
  }

  @Test
  void databaseAlsoRejectsASecondActiveRunForTheSameUser() {
    persistence.allocate(502L, new SandboxRunRequest("first", "PYTHON", null, null));

    assertThrows(SandboxBusyException.class, () -> persistence.allocate(
        502L, new SandboxRunRequest("second", "PYTHON", null, null)));
  }

  @Test
  void concurrentCrossThreadAdmissionCreatesExactlyOneActiveSession() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger();
    AtomicInteger busy = new AtomicInteger();
    try (var callers = Executors.newFixedThreadPool(2)) {
      var tasks = java.util.stream.IntStream.range(0, 2)
          .mapToObj(index -> callers.submit(() -> {
            ready.countDown();
            start.await();
            try {
              persistence.allocate(504L,
                  new SandboxRunRequest("run-" + index, "PYTHON", null, null));
              accepted.incrementAndGet();
            } catch (SandboxBusyException expected) {
              busy.incrementAndGet();
            }
            return null;
          }))
          .toList();
      assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var task : tasks) {
        task.get(2, TimeUnit.SECONDS);
      }
    }

    assertThat(accepted.get()).isEqualTo(1);
    assertThat(busy.get()).isEqualTo(1);
    assertThat(sessions.findByUserIdOrderByStartedAtDesc(
        504L, org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(1);
  }

  @Test
  void crossPodAdmissionLockContentionRejectsImmediatelyInsteadOfBreakingOneSecondBudget()
      throws Exception {
    var lockConnection = dataSource.getConnection();
    lockConnection.setAutoCommit(false);
    lockConnection.createStatement().executeQuery("SELECT pg_advisory_xact_lock(506)").next();
    Thread releaser = new Thread(() -> {
      try {
        Thread.sleep(500L);
        lockConnection.rollback();
        lockConnection.close();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    releaser.start();

    long startedAt = System.nanoTime();
    assertThrows(SandboxBusyException.class, () -> persistence.allocate(
        506L, new SandboxRunRequest("print(1)", "PYTHON", null, null)));
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(elapsedMs).isLessThan(250L);
    releaser.join();
  }

  @Test
  void timedOutTerminalAndTruncationCommitWithOutbox() {
    SandboxSession allocated = persistence.allocate(
        503L, new SandboxRunRequest("loop", "PYTHON", null, null));
    persistence.markRunning(allocated.getId());

    SandboxSession terminal = persistence.finish(allocated.getId(), new RunResult(
        SandboxTerminalStatus.TIMED_OUT,
        -1,
        "partial",
        "timeout",
        null,
        null,
        true));

    assertThat(terminal.getStatus()).isEqualTo("TIMED_OUT");
    assertThat(terminal.isOutputTruncated()).isTrue();
    assertThat(terminal.getFinishedAt()).isNotNull();
    assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
        .anySatisfy(entry -> assertThat(entry.getAggregateId())
            .isEqualTo(String.valueOf(allocated.getId())));
  }

  @Test
  void terminalReplayDoesNotDuplicateOutboxOrOverwriteOutcome() {
    SandboxSession allocated = persistence.allocate(
        505L, new SandboxRunRequest("print(1)", "PYTHON", null, null));
    persistence.markRunning(allocated.getId());
    persistence.finish(allocated.getId(), new RunResult(
        SandboxTerminalStatus.COMPLETED, 0, "ok", "", null, null, false));

    SandboxSession replay = persistence.finish(allocated.getId(), new RunResult(
        SandboxTerminalStatus.FAILED, 1, "", "late", null, null, false));

    assertThat(replay.getStatus()).isEqualTo("COMPLETED");
    assertThat(replay.getStdout()).isEqualTo("ok");
    assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).hasSize(1);
  }

  @Test
  void existingOutboxIsAPointOfNoReturnEvenForTheTerminalFallback() {
    SandboxSession allocated = persistence.allocate(
        506L, new SandboxRunRequest("print('active')", "PYTHON", null, null));
    persistence.markRunning(allocated.getId());
    assertThat(eventPublisher.publishSubmitted(
        allocated.getId(), allocated.getUserId(), allocated.getLanguage(), allocated.getContentId()))
        .isTrue();
    var pointOfNoReturn = terminalOutbox(allocated.getId());

    assertThrows(IllegalStateException.class, () -> persistence.finishWithoutEvent(
        allocated.getId(),
        new RunResult(SandboxTerminalStatus.COMPLETED, 0, "must-not-stick", "", null, null, false)));

    SandboxSession unchanged = sessions.findById(allocated.getId()).orElseThrow();
    var unchangedEvent = terminalOutbox(allocated.getId());
    assertThat(unchanged.getStatus()).isEqualTo("RUNNING");
    assertThat(unchanged.getStdout()).isNull();
    assertThat(unchangedEvent.getId()).isEqualTo(pointOfNoReturn.getId());
    assertThat(unchangedEvent.getPayload()).isEqualTo(pointOfNoReturn.getPayload());
    assertThat(unchangedEvent.getCreatedAt()).isEqualTo(pointOfNoReturn.getCreatedAt());
  }

  @Test
  void staleAllocatingAndRunningSessionsAreReconciledToExplicitTerminals() {
    long allocatingId = saveStale(601L, "ALLOCATING");
    long runningId = saveStale(602L, "RUNNING");
    Instant firstClaimAt = Instant.now();

    int firstPass = persistence.reconcileExpiredAs(
        "other-pod:11111111-1111-1111-1111-111111111111",
        firstClaimAt,
        firstClaimAt.minusSeconds(35),
        100);

    assertThat(firstPass).isZero();
    assertThat(sessions.findById(allocatingId).orElseThrow().getStatus()).isEqualTo("ALLOCATING");
    assertThat(sessions.findById(runningId).orElseThrow().getStatus()).isEqualTo("RUNNING");
    assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).isEmpty();

    int reconciled = persistence.reconcileExpiredAs(
        "other-pod:11111111-1111-1111-1111-111111111111",
        firstClaimAt.plusSeconds(31),
        firstClaimAt.minusSeconds(35),
        100);

    assertThat(reconciled).isEqualTo(2);
    assertThat(sessions.findById(allocatingId).orElseThrow().getStatus()).isEqualTo("FAILED");
    assertThat(sessions.findById(runningId).orElseThrow().getStatus()).isEqualTo("KILLED");
    assertThat(sessions.findById(runningId).orElseThrow().getTerminalSource())
        .isEqualTo("RECONCILER");
    assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).isEmpty();
    assertThat(persistence.repairMissingTerminalEvents(
        100, firstClaimAt.plusSeconds(30))).isZero();
    assertThat(persistence.repairMissingTerminalEvents(
        100, firstClaimAt.plusSeconds(62))).isEqualTo(2);
    assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).hasSize(2);
  }

  @Test
  void recentlyTransitionedRunningSessionIsNotKilledBecauseAllocationStartedEarlier() {
    SandboxSession session = new SandboxSession();
    session.setUserId(603L);
    session.setLanguage("PYTHON");
    session.setSubmittedCode("still-running");
    session.setStatus("RUNNING");
    session.setStartedAt(Instant.now().minusSeconds(60));
    session.setUpdatedAt(Instant.now());
    long id = sessions.saveAndFlush(session).getId();

    int reconciled = persistence.reconcileStale(Instant.now().minusSeconds(35));

    assertThat(reconciled).isZero();
    assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo("RUNNING");
  }

  @Test
  void accurateTerminalFallbackIsRepairedToExactlyOneOutboxRow() {
    SandboxSession allocated = persistence.allocate(
        507L, new SandboxRunRequest("loop", "PYTHON", null, null));
    persistence.markRunning(allocated.getId());
    RunResult actual = new RunResult(
        SandboxTerminalStatus.TIMED_OUT, -1, "partial", "deadline", 29_000L, 128, true);

    persistence.finishWithoutEvent(allocated.getId(), actual);

    assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).isEmpty();
    assertThat(persistence.repairMissingTerminalEvents(25)).isEqualTo(1);
    assertThat(persistence.repairMissingTerminalEvents(25)).isZero();
    SandboxSession terminal = sessions.findById(allocated.getId()).orElseThrow();
    assertThat(terminal.getStatus()).isEqualTo("TIMED_OUT");
    assertThat(terminal.getStdout()).isEqualTo("partial");
    assertThat(terminal.getStderr()).isEqualTo("deadline");
    assertThat(terminal.getCpuMsUsed()).isEqualTo(29_000L);
    assertThat(terminal.getMemoryMbPeak()).isEqualTo(128);
    assertThat(terminal.isOutputTruncated()).isTrue();
    assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).hasSize(1);
  }

  @Test
  void liveFortySecondRunIsNotKilledWhileItsIndependentLeaseIsValid() {
    SandboxSession session = new SandboxSession();
    session.setUserId(604L);
    session.setLanguage("PYTHON");
    session.setSubmittedCode("still-running-after-40s");
    session.setStatus("RUNNING");
    session.setOwnerInstance("pod-a");
    session.setLeaseExpiresAt(Instant.now().plusSeconds(10));
    session.setStartedAt(Instant.now().minusSeconds(40));
    session.setUpdatedAt(Instant.now().minusSeconds(40));
    long id = sessions.saveAndFlush(session).getId();

    int reconciled = persistence.reconcileExpired(
        Instant.now(), Instant.now().minusSeconds(35), 25);

    assertThat(reconciled).isZero();
    assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo("RUNNING");
  }

  @Test
  void expiredLeaseIsRecoveredEvenWhenUpdatedAtWasRecentlyTouched() {
    SandboxSession session = new SandboxSession();
    session.setUserId(605L);
    session.setLanguage("PYTHON");
    session.setSubmittedCode("lost-process");
    session.setStatus("RUNNING");
    session.setOwnerInstance("dead-pod");
    session.setLeaseExpiresAt(Instant.now().minusSeconds(1));
    session.setStartedAt(Instant.now().minusSeconds(20));
    session.setUpdatedAt(Instant.now());
    long id = sessions.saveAndFlush(session).getId();

    Instant firstClaimAt = Instant.now();
    int firstPass = persistence.reconcileExpiredAs(
        "recovery-pod:22222222-2222-2222-2222-222222222222",
        firstClaimAt, firstClaimAt.minusSeconds(35), 25);
    int reconciled = persistence.reconcileExpiredAs(
        "recovery-pod:22222222-2222-2222-2222-222222222222",
        firstClaimAt.plusSeconds(31), firstClaimAt.minusSeconds(35), 25);

    assertThat(firstPass).isZero();
    assertThat(reconciled).isEqualTo(1);
    assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo("KILLED");
  }

  @Test
  void expiredLeaseBecomesTerminalWithinTheThirtyFiveSecondStaleSlo() {
    Instant leaseExpiredAt = Instant.parse("2026-08-16T00:00:00Z");
    SandboxSession session = new SandboxSession();
    session.setUserId(610L);
    session.setLanguage("PYTHON");
    session.setSubmittedCode("lost-process");
    session.setStatus("RUNNING");
    session.setOwnerInstance("dead-process");
    session.setLeaseExpiresAt(leaseExpiredAt);
    long id = sessions.saveAndFlush(session).getId();
    SandboxRunPersistenceService repairPod = persistenceFor(
        new SandboxInstanceIdentity(
            "repair-pod", UUID.fromString("88888888-8888-8888-8888-888888888888")),
        eventPublisher);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);

    int firstPass = transactions.execute(ignored -> repairPod.reconcileExpiredAs(
        "repair-pod:88888888-8888-8888-8888-888888888888",
        leaseExpiredAt.plusSeconds(5), leaseExpiredAt.minusSeconds(35), 25));
    assertThat(firstPass).isZero();
    int beforeDeadline = transactions.execute(ignored -> repairPod.reconcileExpiredAs(
        "repair-pod:88888888-8888-8888-8888-888888888888",
        leaseExpiredAt.plusSeconds(34), leaseExpiredAt.minusSeconds(35), 25));
    assertThat(beforeDeadline).isZero();
    assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo("RUNNING");

    int atDeadline = transactions.execute(ignored -> repairPod.reconcileExpiredAs(
        "repair-pod:88888888-8888-8888-8888-888888888888",
        leaseExpiredAt.plusSeconds(35), leaseExpiredAt.minusSeconds(35), 25));
    assertThat(atDeadline).isEqualTo(1);
    assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo("KILLED");
  }

  @Test
  void samePodRestartGetsANewIncarnationAndCannotRenewTheOldProcessRows() {
    SandboxInstanceIdentity oldProcess =
        new SandboxInstanceIdentity("sandbox-0", UUID.fromString("00000000-0000-0000-0000-000000000001"));
    SandboxInstanceIdentity restartedProcess =
        new SandboxInstanceIdentity("sandbox-0", UUID.fromString("00000000-0000-0000-0000-000000000002"));
    SandboxSession session = new SandboxSession();
    session.setUserId(607L);
    session.setLanguage("PYTHON");
    session.setSubmittedCode("old-process");
    session.setStatus("RUNNING");
    session.setOwnerInstance(oldProcess.value());
    session.setLeaseExpiresAt(Instant.now().minusSeconds(1));
    long id = sessions.saveAndFlush(session).getId();

    int renewed = persistence.renewOwnedLeases(restartedProcess.value());

    assertThat(oldProcess.value()).startsWith("sandbox-0:");
    assertThat(restartedProcess.value()).startsWith("sandbox-0:");
    assertThat(restartedProcess.value()).isNotEqualTo(oldProcess.value());
    assertThat(renewed).isZero();
    assertThat(sessions.findById(id).orElseThrow().getLeaseExpiresAt())
        .isBefore(Instant.now());
  }

  @Test
  void currentIncarnationCannotReconcileAnExpiredPendingExactResult() {
    for (RunResult exact : java.util.List.of(
        new RunResult(SandboxTerminalStatus.COMPLETED, 0, "exact-output", "", 15L, 20, true),
        new RunResult(SandboxTerminalStatus.TIMED_OUT, -1, "partial", "deadline", 30_000L, 64, true))) {
      long userId = exact.terminalStatus() == SandboxTerminalStatus.COMPLETED ? 608L : 609L;
      SandboxSession allocated = persistence.allocate(
          userId, new SandboxRunRequest("run", "PYTHON", null, null));
      persistence.markRunning(allocated.getId());
      SandboxSession expired = sessions.findById(allocated.getId()).orElseThrow();
      expired.setLeaseExpiresAt(Instant.now().minusSeconds(1));
      sessions.saveAndFlush(expired);

      assertThat(persistence.reconcileExpired(
          Instant.now(), Instant.now().minusSeconds(35), 25)).isZero();
      persistence.finish(allocated.getId(), exact);

      SandboxSession terminal = sessions.findById(allocated.getId()).orElseThrow();
      assertThat(terminal.getStatus()).isEqualTo(exact.terminalStatus().name());
      assertThat(terminal.getStdout()).isEqualTo(exact.stdout());
      assertThat(terminal.getStderr()).isEqualTo(exact.stderr());
      assertThat(terminal.isOutputTruncated()).isEqualTo(exact.outputTruncated());
    }
  }

  @Test
  void reconciliationBatchSkipsRowsLockedByAnotherWorker() throws Exception {
    SandboxSession session = new SandboxSession();
    session.setUserId(606L);
    session.setLanguage("PYTHON");
    session.setSubmittedCode("owned-by-other-reconciler");
    session.setStatus("RUNNING");
    session.setOwnerInstance("dead-pod");
    session.setLeaseExpiresAt(Instant.now().minusSeconds(1));
    long id = sessions.saveAndFlush(session).getId();

    try (var lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement = lock.prepareStatement(
          "SELECT id FROM sandbox_sessions WHERE id = ? FOR UPDATE")) {
        statement.setLong(1, id);
        statement.executeQuery().next();

        long started = System.nanoTime();
        int reconciled = persistence.reconcileExpired(
            Instant.now(), Instant.now().minusSeconds(35), 25);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(reconciled).isZero();
        assertThat(elapsedMs).isLessThan(250L);
      } finally {
        lock.rollback();
      }
    }

    Instant firstClaimAt = Instant.now();
    assertThat(persistence.reconcileExpiredAs(
        "recovery-pod:33333333-3333-3333-3333-333333333333",
        firstClaimAt, firstClaimAt.minusSeconds(35), 25)).isZero();
    assertThat(persistence.reconcileExpiredAs(
        "recovery-pod:33333333-3333-3333-3333-333333333333",
        firstClaimAt.plusSeconds(31), firstClaimAt.minusSeconds(35), 25)).isEqualTo(1);
  }

  @Test
  void otherPodReconcilerCannotFreezeAWrongTerminalBeforePendingExactRetry() {
    SandboxRunPersistenceService podB = new SandboxRunPersistenceService(
        sessions,
        eventPublisher,
        entityManager,
        new SandboxInstanceIdentity(
            "pod-b", UUID.fromString("44444444-4444-4444-4444-444444444444")),
        20_000L,
        30_000L,
        30_000L);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    int sequence = 0;
    for (RunResult exact : java.util.List.of(
        new RunResult(SandboxTerminalStatus.COMPLETED, 0,
            "exact-complete", "", 25L, 32, false),
        new RunResult(SandboxTerminalStatus.TIMED_OUT, -1,
            "exact-partial", "deadline", 30_000L, 64, true))) {
      long userId = 620L + sequence++;
      SandboxSession allocated = persistence.allocate(
          userId, new SandboxRunRequest("run", "PYTHON", null, null));
      persistence.markRunning(allocated.getId());
      SandboxSession expired = sessions.findById(allocated.getId()).orElseThrow();
      expired.setLeaseExpiresAt(Instant.now().minusSeconds(1));
      sessions.saveAndFlush(expired);

      java.util.concurrent.atomic.AtomicBoolean databaseDown =
          new java.util.concurrent.atomic.AtomicBoolean(true);
      SandboxRunPersistenceService gatedPersistence = mock(SandboxRunPersistenceService.class);
      when(gatedPersistence.finish(allocated.getId(), exact)).thenAnswer(invocation -> {
        if (databaseDown.get()) throw new IllegalStateException("database down");
        return persistence.finish(allocated.getId(), exact);
      });
      when(gatedPersistence.finishWithoutEvent(allocated.getId(), exact)).thenAnswer(invocation -> {
        if (databaseDown.get()) throw new IllegalStateException("database down");
        return persistence.finishWithoutEvent(allocated.getId(), exact);
      });
      SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(
          gatedPersistence, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 1);
      var reservation = finalizer.reserve();
      assertThrows(RuntimeException.class,
          () -> finalizer.finish(allocated.getId(), exact, reservation));

      Instant recoveredAt = Instant.now();
      int firstClaim = transactions.execute(ignored -> podB.reconcileExpired(
          recoveredAt, recoveredAt.minusSeconds(35), 25));
      assertThat(firstClaim).isZero();
      assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
          .noneSatisfy(entry -> assertThat(entry.getAggregateId())
              .isEqualTo(String.valueOf(allocated.getId())));

      int reconciled = transactions.execute(ignored -> podB.reconcileExpired(
          recoveredAt.plusSeconds(31), recoveredAt.minusSeconds(35), 25));
      assertThat(reconciled).isEqualTo(1);
      SandboxSession inferred = sessions.findById(allocated.getId()).orElseThrow();
      assertThat(inferred.getStatus()).isEqualTo("KILLED");
      assertThat(inferred.getTerminalSource()).isEqualTo("RECONCILER");
      assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
          .noneSatisfy(entry -> assertThat(entry.getAggregateId())
              .isEqualTo(String.valueOf(allocated.getId())));

      databaseDown.set(false);
      assertThat(finalizer.retryPending()).isEqualTo(1);

      SandboxSession corrected = sessions.findById(allocated.getId()).orElseThrow();
      assertThat(corrected.getStatus()).isEqualTo(exact.terminalStatus().name());
      assertThat(corrected.getStdout()).isEqualTo(exact.stdout());
      assertThat(corrected.getStderr()).isEqualTo(exact.stderr());
      assertThat(corrected.isOutputTruncated()).isEqualTo(exact.outputTruncated());
      assertThat(corrected.getTerminalSource()).isEqualTo("RUNNER");
      assertThat(corrected.getReconciliationStartedAt()).isNull();
      assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
          .filteredOn(entry -> entry.getAggregateId().equals(String.valueOf(allocated.getId())))
          .hasSize(1);
    }
  }

  @Test
  void repairLockWinsAndMakesPendingAndPublishedInferredEventImmutable() throws Exception {
    InferredRun inferred = createInferredRun(630L);
    CountDownLatch repairHasRow = new CountDownLatch(1);
    CountDownLatch allowRepairToPublish = new CountDownLatch(1);
    SandboxRunEventPublisher gatedPublisher = mock(SandboxRunEventPublisher.class);
    doAnswer(invocation -> {
      repairHasRow.countDown();
      if (!allowRepairToPublish.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("repair publish gate timed out");
      }
      return eventPublisher.publishSubmitted(
          invocation.getArgument(0),
          invocation.getArgument(1),
          invocation.getArgument(2),
          invocation.getArgument(3));
    }).when(gatedPublisher).publishSubmitted(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.nullable(Long.class));
    SandboxRunPersistenceService repairPod = persistenceFor(
        new SandboxInstanceIdentity(
            "pod-repair", UUID.fromString("55555555-5555-5555-5555-555555555555")),
        gatedPublisher);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    RunResult exact = new RunResult(
        SandboxTerminalStatus.COMPLETED, 0, "exact-after-repair", "", 11L, 22, false);

    try (var callers = Executors.newFixedThreadPool(2)) {
      var repair = callers.submit(() -> transactions.execute(ignored ->
          repairPod.repairMissingTerminalEvents(25, inferred.publishCutoff())));
      assertThat(repairHasRow.await(1, TimeUnit.SECONDS)).isTrue();

      CountDownLatch exactCalling = new CountDownLatch(1);
      var exactRetry = callers.submit(() -> {
        exactCalling.countDown();
        return persistence.finish(inferred.sessionId(), exact);
      });
      assertThat(exactCalling.await(1, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(100L);
      assertThat(exactRetry.isDone()).isFalse();

      allowRepairToPublish.countDown();
      assertThat(repair.get(2, TimeUnit.SECONDS)).isEqualTo(1);
      SandboxSession canonical = exactRetry.get(2, TimeUnit.SECONDS);
      assertThat(canonical.getStatus()).isEqualTo("KILLED");
      assertThat(canonical.getTerminalSource()).isEqualTo("RECONCILER");
    }

    var pendingEvent = terminalOutbox(inferred.sessionId());
    Long eventId = pendingEvent.getId();
    Instant eventCreatedAt = pendingEvent.getCreatedAt();
    String eventPayload = pendingEvent.getPayload();
    Instant inferredFinishedAt = sessions.findById(inferred.sessionId()).orElseThrow().getFinishedAt();

    SandboxTerminalFinalizer finalizer = new SandboxTerminalFinalizer(
        persistence,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        1,
        1,
        SandboxTerminalFinalizer.RESULT_RESERVATION_BYTES,
        java.time.Duration.ofSeconds(1));
    SandboxSession pendingCanonical = finalizer.finish(inferred.sessionId(), exact);
    assertThat(pendingCanonical.getStatus()).isEqualTo("KILLED");
    assertThat(finalizer.pendingCount()).isZero();
    assertThat(finalizer.hasCapacity()).isTrue();

    pendingEvent.setPublishedAt(Instant.now());
    outbox.saveAndFlush(pendingEvent);
    SandboxSession publishedCanonical = persistence.finish(inferred.sessionId(), exact);

    SandboxSession unchanged = sessions.findById(inferred.sessionId()).orElseThrow();
    var unchangedEvent = terminalOutbox(inferred.sessionId());
    assertThat(unchanged.getStatus()).isEqualTo("KILLED");
    assertThat(unchanged.getTerminalSource()).isEqualTo("RECONCILER");
    assertThat(unchanged.getFinishedAt()).isEqualTo(inferredFinishedAt);
    assertThat(publishedCanonical.getStatus()).isEqualTo("KILLED");
    assertThat(unchangedEvent.getId()).isEqualTo(eventId);
    assertThat(unchangedEvent.getCreatedAt()).isEqualTo(eventCreatedAt);
    assertThat(unchangedEvent.getPayload()).isEqualTo(eventPayload);
    assertThat(outbox.findAll())
        .filteredOn(entry -> entry.getAggregateId().equals(String.valueOf(inferred.sessionId())))
        .hasSize(1);
  }

  @Test
  void exactLockWinsAndRepairSkipsWithoutPublishingInferredEvent() throws Exception {
    InferredRun inferred = createInferredRun(631L);
    CountDownLatch exactHasRow = new CountDownLatch(1);
    CountDownLatch allowExactToPublish = new CountDownLatch(1);
    SandboxRunEventPublisher gatedPublisher = mock(SandboxRunEventPublisher.class);
    doAnswer(invocation -> {
      exactHasRow.countDown();
      if (!allowExactToPublish.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("exact publish gate timed out");
      }
      return eventPublisher.publishSubmitted(
          invocation.getArgument(0),
          invocation.getArgument(1),
          invocation.getArgument(2),
          invocation.getArgument(3));
    }).when(gatedPublisher).publishSubmitted(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.nullable(Long.class));
    SandboxRunPersistenceService exactOwner = persistenceFor(instanceIdentity, gatedPublisher);
    SandboxRunPersistenceService repairPod = persistenceFor(
        new SandboxInstanceIdentity(
            "pod-repair", UUID.fromString("66666666-6666-6666-6666-666666666666")),
        eventPublisher);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    RunResult exact = new RunResult(
        SandboxTerminalStatus.TIMED_OUT, -1, "exact-partial", "deadline", 30_000L, 64, true);

    try (var callers = Executors.newFixedThreadPool(2)) {
      var exactRetry = callers.submit(() -> transactions.execute(ignored ->
          exactOwner.finish(inferred.sessionId(), exact)));
      assertThat(exactHasRow.await(1, TimeUnit.SECONDS)).isTrue();

      int repaired;
      try {
        repaired = transactions.execute(ignored ->
            repairPod.repairMissingTerminalEvents(25, inferred.publishCutoff()));
      } finally {
        allowExactToPublish.countDown();
      }
      assertThat(repaired).isZero();
      SandboxSession terminal = exactRetry.get(2, TimeUnit.SECONDS);
      assertThat(terminal.getStatus()).isEqualTo("TIMED_OUT");
      assertThat(terminal.getTerminalSource()).isEqualTo("RUNNER");
    }

    SandboxSession terminal = sessions.findById(inferred.sessionId()).orElseThrow();
    assertThat(terminal.getStatus()).isEqualTo("TIMED_OUT");
    assertThat(terminal.getStdout()).isEqualTo("exact-partial");
    assertThat(terminal.getStderr()).isEqualTo("deadline");
    assertThat(terminal.isOutputTruncated()).isTrue();
    assertThat(outbox.findAll())
        .filteredOn(entry -> entry.getAggregateId().equals(String.valueOf(inferred.sessionId())))
        .hasSize(1);
  }

  private long saveStale(long userId, String status) {
    SandboxSession session = new SandboxSession();
    session.setUserId(userId);
    session.setLanguage("PYTHON");
    session.setSubmittedCode("stale");
    session.setStatus(status);
    session.setStartedAt(Instant.now().minusSeconds(60));
    session.setUpdatedAt(Instant.now().minusSeconds(60));
    return sessions.saveAndFlush(session).getId();
  }

  private InferredRun createInferredRun(long userId) {
    SandboxSession allocated = persistence.allocate(
        userId, new SandboxRunRequest("run", "PYTHON", null, null));
    persistence.markRunning(allocated.getId());
    SandboxSession expired = sessions.findById(allocated.getId()).orElseThrow();
    expired.setLeaseExpiresAt(Instant.now().minusSeconds(1));
    sessions.saveAndFlush(expired);
    Instant claimedAt = Instant.now();
    SandboxRunPersistenceService repairPod = persistenceFor(
        new SandboxInstanceIdentity(
            "pod-claim", UUID.fromString("77777777-7777-7777-7777-777777777777")),
        eventPublisher);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    int claimed = transactions.execute(ignored -> repairPod.reconcileExpired(
        claimedAt, claimedAt.minusSeconds(35), 25));
    assertThat(claimed).isZero();
    Instant inferredAt = claimedAt.plusSeconds(31);
    int reconciled = transactions.execute(ignored -> repairPod.reconcileExpired(
        inferredAt, claimedAt.minusSeconds(35), 25));
    assertThat(reconciled).isEqualTo(1);
    return new InferredRun(allocated.getId(), inferredAt.plusSeconds(1));
  }

  private SandboxRunPersistenceService persistenceFor(
      SandboxInstanceIdentity identity,
      SandboxRunEventPublisher publisher) {
    return new SandboxRunPersistenceService(
        sessions, publisher, entityManager, identity, 20_000L, 30_000L, 30_000L);
  }

  private ai.devpath.sandbox.outbox.OutboxEntry terminalOutbox(long sessionId) {
    return outbox.findAll().stream()
        .filter(entry -> entry.getAggregateId().equals(String.valueOf(sessionId)))
        .findFirst()
        .orElseThrow();
  }

  private record InferredRun(long sessionId, Instant publishCutoff) {}
}
