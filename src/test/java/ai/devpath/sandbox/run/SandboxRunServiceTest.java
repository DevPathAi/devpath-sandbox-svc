package ai.devpath.sandbox.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.devpath.sandbox.outbox.OutboxRepository;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class SandboxRunServiceTest {

  @Autowired SandboxRunService service;
  @Autowired OutboxRepository outboxRepo;
  @MockitoBean RunnerBackend runnerBackend;

  @Test
  void successfulRunCompletesSessionAndPublishesEvent() {
    when(runnerBackend.run(any(), any()))
        .thenReturn(new RunResult(0, "ok\n", "", 120L, 24));

    SandboxSession session = service.execute(
        42L, new SandboxRunRequest("print(1)", "PYTHON", 10L, 20L), line -> {});

    assertNotNull(session.getId());
    assertEquals("COMPLETED", session.getStatus());
    assertEquals(0, session.getExitCode());
    assertEquals("ok\n", session.getStdout());
    assertEquals(42L, session.getUserId());
    assertEquals(10L, session.getContentId());
    assertEquals(20L, session.getCodeBlockId());
  }

  @Test
  void nonZeroExitCodeMarksFailed() {
    when(runnerBackend.run(any(), any()))
        .thenReturn(new RunResult(1, "", "boom\n", 50L, 12));

    SandboxSession session = service.execute(
        2L, new SandboxRunRequest("raise", "PYTHON", null, null), line -> {});

    assertEquals("FAILED", session.getStatus());
    assertEquals(1, session.getExitCode());
    assertEquals("boom\n", session.getStderr());
  }

  @Test
  void minusOneExitCodeMarksKilled() {
    when(runnerBackend.run(any(), any()))
        .thenReturn(new RunResult(-1, "", "실행 시간 초과(30s)\n", null, null));

    SandboxSession session = service.execute(
        3L, new SandboxRunRequest("while True: pass", "PYTHON", null, null), line -> {});

    assertEquals("KILLED", session.getStatus());
  }

  @Test
  void unavailableBackendThrowsSandboxUnavailableException() {
    when(runnerBackend.run(any(), any()))
        .thenThrow(new SandboxUnavailableException("Docker 미가동"));

    assertThrows(SandboxUnavailableException.class, () ->
        service.execute(4L, new SandboxRunRequest("print(1)", "PYTHON", null, null), line -> {}));
  }

  @Test
  void outboxEntryIsCreatedForEachRun() {
    when(runnerBackend.run(any(), any()))
        .thenReturn(new RunResult(0, "ok\n", "", 60L, 10));

    long countBefore = outboxRepo.count();
    service.execute(6L, new SandboxRunRequest("x=1", "PYTHON", null, null), line -> {});
    long countAfter = outboxRepo.count();

    assertEquals(countBefore + 1, countAfter, "outbox에 1건 추가");
  }

  @Test
  void logCallbackIsInvokedForEachLogLine() {
    when(runnerBackend.run(any(), any()))
        .thenAnswer(inv -> {
          Consumer<String> cb = inv.getArgument(1);
          cb.accept("log-line-A");
          cb.accept("log-line-B");
          return new RunResult(0, "log-line-A\nlog-line-B\n", "", null, null);
        });

    var received = new java.util.ArrayList<String>();
    service.execute(7L, new SandboxRunRequest("x=1", "PYTHON", null, null), received::add);

    assertEquals(List.of("log-line-A", "log-line-B"), received);
  }

  @Test
  void backendFailureDoesNotPublishEvent() {
    when(runnerBackend.run(any(), any()))
        .thenThrow(new SandboxUnavailableException("Docker 미가동"));

    long before = outboxRepo.count();
    assertThrows(SandboxUnavailableException.class, () ->
        service.execute(8L, new SandboxRunRequest("print(1)", "PYTHON", null, null), line -> {}));

    assertEquals(before, outboxRepo.count(),
        "실행이 완료되지 못하면(run throw) 리뷰 이벤트를 발행하지 않는다(발행은 finish에서)");
  }
}
