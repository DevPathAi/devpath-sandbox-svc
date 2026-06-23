package ai.devpath.sandbox.run;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 샌드박스 세션 영속을 짧은 트랜잭션으로 분리한다.
 * 컨테이너 실행(최대 30s)은 호출자(SandboxRunService)가 트랜잭션 밖에서 수행하고,
 * 세션 생성/이벤트 발행/결과 반영만 이 서비스의 짧은 @Transactional로 감싼다.
 * (learning-svc LearningPathPersistenceService 패턴 — 외부 I/O가 DB 커넥션을 점유하지 않도록)
 */
@Service
public class SandboxRunPersistenceService {

  private final SandboxSessionRepository sessions;
  private final SandboxRunEventPublisher eventPublisher;

  public SandboxRunPersistenceService(SandboxSessionRepository sessions,
      SandboxRunEventPublisher eventPublisher) {
    this.sessions = sessions;
    this.eventPublisher = eventPublisher;
  }

  /** 세션 생성(ALLOCATING) + 제출 이벤트 발행(outbox) + RUNNING 전이. 짧은 tx. */
  @Transactional
  public SandboxSession createRunning(long userId, SandboxRunRequest req) {
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
    return sessions.save(session);
  }

  /** 실행 결과 반영(상태머신·stdout/stderr·exit·리소스 사용량). 짧은 tx. */
  @Transactional
  public SandboxSession finish(SandboxSession session, RunResult result) {
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
