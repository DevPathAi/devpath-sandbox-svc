package ai.devpath.sandbox.run;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SandboxSessionRepository extends JpaRepository<SandboxSession, Long> {

  /** 사용자별 최근 실행(started_at DESC). limit은 Pageable로 적용(인덱스 idx_sandbox_user_started 정합). */
  List<SandboxSession> findByUserIdOrderByStartedAtDesc(long userId, Pageable pageable);
}
