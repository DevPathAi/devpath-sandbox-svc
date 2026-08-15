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
    assertThat(sessions.findById(allocated.getId()).orElseThrow().getStatus())
        .isEqualTo("ALLOCATING");

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
