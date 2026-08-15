package ai.devpath.sandbox.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEntry, Long> {
  List<OutboxEntry> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

  @Modifying
  @Query(value = """
      INSERT INTO outbox (
        aggregate_type, aggregate_id, event_type, payload, created_at, dedupe_key
      )
      SELECT 'sandbox_session', :aggregateId, :eventType, CAST(:payload AS jsonb),
             :createdAt, :dedupeKey
      WHERE NOT EXISTS (
        SELECT 1
        FROM outbox existing
        WHERE existing.aggregate_type = 'sandbox_session'
          AND existing.aggregate_id = :aggregateId
          AND existing.event_type = :eventType
      )
      ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
      """, nativeQuery = true)
  int insertSandboxTerminalOnce(
      @Param("aggregateId") String aggregateId,
      @Param("eventType") String eventType,
      @Param("payload") String payload,
      @Param("createdAt") java.time.Instant createdAt,
      @Param("dedupeKey") String dedupeKey);
}
