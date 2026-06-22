package ai.devpath.sandbox.run;

import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SandboxRunService {

  private final SandboxSessionRepository sessions;
  private final RunnerBackend runnerBackend;
  private final SandboxRunEventPublisher eventPublisher;

  public SandboxRunService(SandboxSessionRepository sessions,
      RunnerBackend runnerBackend,
      SandboxRunEventPublisher eventPublisher) {
    this.sessions = sessions;
    this.runnerBackend = runnerBackend;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public SandboxSession execute(long userId, SandboxRunRequest req, Consumer<String> logCallback) {
    SandboxSession session = new SandboxSession();
    session.setUserId(userId);
    session.setLanguage(req.language());
    session.setSubmittedCode(req.code());
    session.setContentId(req.contentId());
    session.setCodeBlockId(req.codeBlockId());
    session.setStatus("ALLOCATING");
    session.setStartedAt(Instant.now());
    session = sessions.save(session);

    eventPublisher.publishSubmitted(session.getId(), userId, req.language(), req.contentId());

    session.setStatus("RUNNING");
    sessions.save(session);

    RunResult result = runnerBackend.run(
        new RunSpec(req.code(), req.language(), session.getId()),
        logCallback);

    session.setFinishedAt(Instant.now());
    session.setExitCode(result.exitCode());
    session.setStdout(result.stdout());
    session.setStderr(result.stderr());
    session.setCpuMsUsed(result.cpuMsUsed());
    session.setMemoryMbPeak(result.memoryMbPeak());

    if (result.exitCode() == 0) {
      session.setStatus("COMPLETED");
    } else if (result.exitCode() == -1) {
      session.setStatus("KILLED");
    } else {
      session.setStatus("FAILED");
    }

    return sessions.save(session);
  }
}
