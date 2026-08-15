package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.devpath.sandbox.outbox.OutboxRepository;
import java.time.Instant;
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

@SpringBootTest
@ActiveProfiles("test")
class SandboxRunPersistenceLifecycleTest {

  @Autowired SandboxRunPersistenceService persistence;
  @Autowired SandboxSessionRepository sessions;
  @Autowired OutboxRepository outbox;
  @Autowired DataSource dataSource;

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
  void staleAllocatingAndRunningSessionsAreReconciledToExplicitTerminals() {
    long allocatingId = saveStale(601L, "ALLOCATING");
    long runningId = saveStale(602L, "RUNNING");

    int reconciled = persistence.reconcileStale(Instant.now().minusSeconds(35));

    assertThat(reconciled).isEqualTo(2);
    assertThat(sessions.findById(allocatingId).orElseThrow().getStatus()).isEqualTo("FAILED");
    assertThat(sessions.findById(runningId).orElseThrow().getStatus()).isEqualTo("KILLED");
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

    int reconciled = persistence.reconcileExpired(
        Instant.now(), Instant.now().minusSeconds(35), 25);

    assertThat(reconciled).isEqualTo(1);
    assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo("KILLED");
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

    assertThat(persistence.reconcileExpired(
        Instant.now(), Instant.now().minusSeconds(35), 25)).isEqualTo(1);
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
}
