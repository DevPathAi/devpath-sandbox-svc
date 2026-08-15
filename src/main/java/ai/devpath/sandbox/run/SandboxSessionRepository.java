package ai.devpath.sandbox.run;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SandboxSessionRepository extends JpaRepository<SandboxSession, Long> {

  /** 사용자별 최근 실행(started_at DESC). limit은 Pageable로 적용(인덱스 idx_sandbox_user_started 정합). */
  List<SandboxSession> findByUserIdOrderByStartedAtDesc(long userId, Pageable pageable);

  Optional<SandboxSession> findByIdAndUserId(long id, long userId);

  boolean existsByUserIdAndStatusIn(long userId, List<String> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select session from SandboxSession session where session.id = :id")
  Optional<SandboxSession> findByIdForUpdate(@Param("id") long id);

  @Query("select session.id from SandboxSession session "
      + "where session.status in :statuses and session.updatedAt < :cutoff")
  List<Long> findStaleIds(
      @Param("statuses") List<String> statuses,
      @Param("cutoff") java.time.Instant cutoff);
}
