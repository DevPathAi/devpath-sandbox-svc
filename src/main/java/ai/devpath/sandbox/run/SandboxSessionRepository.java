package ai.devpath.sandbox.run;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

  @Query(value = """
      SELECT *
      FROM sandbox_sessions
      WHERE status IN ('ALLOCATING', 'RUNNING')
        AND (owner_instance IS NULL OR owner_instance <> :currentOwner)
        AND (
          (lease_expires_at IS NOT NULL AND lease_expires_at <= :now)
          OR
          (lease_expires_at IS NULL AND updated_at < :legacyCutoff)
        )
      ORDER BY COALESCE(lease_expires_at, updated_at), id
      LIMIT :batchSize
      FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
  List<SandboxSession> findExpiredForReconciliation(
      @Param("now") java.time.Instant now,
      @Param("legacyCutoff") java.time.Instant legacyCutoff,
      @Param("currentOwner") String currentOwner,
      @Param("batchSize") int batchSize);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = """
      UPDATE sandbox_sessions
      SET lease_expires_at = :leaseExpiresAt,
          reconciliation_token = NULL,
          reconciliation_started_at = NULL
      WHERE owner_instance = :ownerInstance
        AND status IN ('ALLOCATING', 'RUNNING')
      """, nativeQuery = true)
  int renewOwnedLeases(
      @Param("ownerInstance") String ownerInstance,
      @Param("leaseExpiresAt") java.time.Instant leaseExpiresAt);

  @Query(value = """
      SELECT count(*)
      FROM sandbox_sessions
      WHERE status IN ('ALLOCATING', 'RUNNING')
        AND (
          (lease_expires_at IS NOT NULL AND lease_expires_at <= :now)
          OR
          (lease_expires_at IS NULL AND updated_at < :legacyCutoff)
        )
      """, nativeQuery = true)
  long countExpiredActive(
      @Param("now") java.time.Instant now,
      @Param("legacyCutoff") java.time.Instant legacyCutoff);

  @Query(value = """
      SELECT session.*
      FROM sandbox_sessions session
      WHERE session.status IN ('COMPLETED', 'FAILED', 'KILLED', 'TIMED_OUT')
        AND (
          session.terminal_source IS NULL
          OR session.terminal_source <> 'RECONCILER'
          OR (
            session.reconciliation_token IS NOT NULL
            AND session.reconciliation_started_at <= :reconciliationPublishCutoff
          )
        )
        AND NOT EXISTS (
          SELECT 1
          FROM outbox event
          WHERE event.dedupe_key = 'sandbox.run.submitted:' || session.id
             OR (
               event.dedupe_key IS NULL
               AND event.aggregate_type = 'sandbox_session'
               AND event.aggregate_id = CAST(session.id AS text)
               AND event.event_type = 'sandbox.run.submitted'
             )
        )
      ORDER BY session.finished_at, session.id
      LIMIT :batchSize
      FOR UPDATE OF session SKIP LOCKED
      """, nativeQuery = true)
  List<SandboxSession> findTerminalWithoutOutbox(
      @Param("reconciliationPublishCutoff") java.time.Instant reconciliationPublishCutoff,
      @Param("batchSize") int batchSize);
}
