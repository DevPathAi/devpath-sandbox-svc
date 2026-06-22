package ai.devpath.sandbox.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEntry, Long> {
  List<OutboxEntry> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
