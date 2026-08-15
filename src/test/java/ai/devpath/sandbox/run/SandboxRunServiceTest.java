package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.devpath.sandbox.outbox.OutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class SandboxRunServiceTest {

  @Autowired SandboxRunService service;
  @Autowired SandboxSessionRepository sessions;
  @Autowired OutboxRepository outbox;
  @MockitoBean RunnerBackend runnerBackend;

  @BeforeEach
  void cleanup() {
    outbox.deleteAll();
    sessions.deleteAll();
  }

  @Test
  void successfulRunCompletesSessionAndPublishesEvent() throws Exception {
    when(runnerBackend.run(any(), any(), any()))
        .thenReturn(new RunResult(0, "ok\n", "", 120L, 24));
    RecordingDelivery delivery = new RecordingDelivery();

    AcceptedSandboxRun accepted = service.start(
        42L, new SandboxRunRequest("print(1)", "PYTHON", 10L, 20L), delivery);
    SandboxSession session = awaitTerminal(accepted.sessionId(), delivery);

    assertThat(session.getStatus()).isEqualTo("COMPLETED");
    assertThat(session.getExitCode()).isZero();
    assertThat(session.getStdout()).isEqualTo("ok\n");
    assertThat(session.getUserId()).isEqualTo(42L);
    assertThat(session.getContentId()).isEqualTo(10L);
    assertThat(session.getCodeBlockId()).isEqualTo(20L);
    assertThat(outbox.count()).isEqualTo(1L);
  }

  @Test
  void explicitTimedOutResultIsNotCollapsedIntoKilled() throws Exception {
    when(runnerBackend.run(any(), any(), any()))
        .thenReturn(RunResult.timedOut("partial", "Execution timed out"));
    RecordingDelivery delivery = new RecordingDelivery();

    AcceptedSandboxRun accepted = service.start(
        43L, new SandboxRunRequest("loop", "PYTHON", null, null), delivery);

    assertThat(awaitTerminal(accepted.sessionId(), delivery).getStatus()).isEqualTo("TIMED_OUT");
  }

  @Test
  void backendFailureStillLeavesADurableFailedTerminal() throws Exception {
    when(runnerBackend.run(any(), any(), any()))
        .thenThrow(new SandboxUnavailableException("Docker unavailable"));
    RecordingDelivery delivery = new RecordingDelivery();

    AcceptedSandboxRun accepted = service.start(
        44L, new SandboxRunRequest("print(1)", "PYTHON", null, null), delivery);

    SandboxSession session = awaitTerminal(accepted.sessionId(), delivery);
    assertThat(session.getStatus()).isEqualTo("FAILED");
    assertThat(outbox.count()).isEqualTo(1L);
  }

  @Test
  void nullBackendResultIsPersistedAsFailedInsteadOfLeavingRunningForever() throws Exception {
    when(runnerBackend.run(any(), any(), any())).thenReturn(null);
    RecordingDelivery delivery = new RecordingDelivery();

    AcceptedSandboxRun accepted = service.start(
        46L, new SandboxRunRequest("print(1)", "PYTHON", null, null), delivery);

    assertThat(awaitTerminal(accepted.sessionId(), delivery).getStatus()).isEqualTo("FAILED");
  }

  @Test
  void logCallbackIsBestEffortAndTerminalPersistenceIsIndependent() throws Exception {
    when(runnerBackend.run(any(), any(), any())).thenAnswer(inv -> {
      java.util.function.Consumer<String> callback = inv.getArgument(1);
      callback.accept("line-A");
      callback.accept("line-B");
      return new RunResult(0, "line-A\nline-B\n", "", null, null);
    });
    RecordingDelivery delivery = new RecordingDelivery();

    AcceptedSandboxRun accepted = service.start(
        45L, new SandboxRunRequest("x=1", "PYTHON", null, null), delivery);

    assertThat(awaitTerminal(accepted.sessionId(), delivery).getStatus()).isEqualTo("COMPLETED");
    assertThat(delivery.logs).containsExactly("line-A", "line-B");
  }

  private SandboxSession awaitTerminal(long sessionId, RecordingDelivery delivery) throws Exception {
    assertThat(delivery.completed.await(2, TimeUnit.SECONDS)).isTrue();
    Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
    while (Instant.now().isBefore(deadline)) {
      SandboxSession session = sessions.findById(sessionId).orElseThrow();
      if (List.of("COMPLETED", "FAILED", "KILLED", "TIMED_OUT")
          .contains(session.getStatus())) {
        return session;
      }
      Thread.sleep(10L);
    }
    throw new AssertionError("terminal session was not persisted");
  }

  private static final class RecordingDelivery implements SandboxRunDelivery {
    private final List<String> logs = new ArrayList<>();
    private final CountDownLatch completed = new CountDownLatch(1);

    @Override public void session(long sessionId) {}
    @Override public void log(String line) { logs.add(line); }
    @Override public void result(SandboxTerminalEvent event) {}
    @Override public void complete() { completed.countDown(); }
  }
}
