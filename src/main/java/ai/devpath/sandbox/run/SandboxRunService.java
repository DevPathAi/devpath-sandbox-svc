package ai.devpath.sandbox.run;

import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * 샌드박스 실행 오케스트레이션.
 * 세션 생성/이벤트/결과 영속은 짧은 @Transactional(SandboxRunPersistenceService),
 * 컨테이너 실행(최대 30s)은 트랜잭션 밖에서 수행해 DB 커넥션을 장기 점유하지 않는다.
 */
@Service
public class SandboxRunService {

  private final SandboxRunPersistenceService persistence;
  private final RunnerBackend runnerBackend;

  public SandboxRunService(SandboxRunPersistenceService persistence,
      RunnerBackend runnerBackend) {
    this.persistence = persistence;
    this.runnerBackend = runnerBackend;
  }

  /**
   * runner 가용성(예: Docker 데몬). RunController가 SSE 시작 전에 호출해
   * 불가 시 세션·이벤트를 생성하지 않고 503으로 빠르게 종료한다.
   */
  public boolean isRunnerAvailable() {
    return runnerBackend.isAvailable();
  }

  public SandboxSession execute(long userId, SandboxRunRequest req, Consumer<String> logCallback) {
    SandboxSession session = persistence.createRunning(userId, req);

    RunResult result = runnerBackend.run(
        new RunSpec(req.code(), req.language(), session.getId()), logCallback);

    return persistence.finish(session, result);
  }
}
