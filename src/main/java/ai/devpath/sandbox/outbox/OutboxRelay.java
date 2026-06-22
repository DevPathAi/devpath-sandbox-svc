package ai.devpath.sandbox.outbox;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

  private final OutboxRepository outbox;
  private final KafkaTemplate<String, String> kafka;

  public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
    this.outbox = outbox;
    this.kafka = kafka;
  }

  public int relayOnce() {
    var batch = outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
    int count = 0;
    for (OutboxEntry e : batch) {
      try {
        kafka.send(e.getEventType(), e.getAggregateId(), e.getPayload()).get(5, TimeUnit.SECONDS);
      } catch (Exception ex) {
        break;
      }
      e.setPublishedAt(Instant.now());
      outbox.save(e);
      count++;
    }
    return count;
  }
}
