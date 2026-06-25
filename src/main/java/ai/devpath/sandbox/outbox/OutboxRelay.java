package ai.devpath.sandbox.outbox;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
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
        log.warn("outbox relay 발행 실패(eventType={}, aggregateId={}) — 미발행 유지, 다음 주기 재시도",
            e.getEventType(), e.getAggregateId(), ex);
        break;
      }
      e.setPublishedAt(Instant.now());
      outbox.save(e);
      count++;
    }
    return count;
  }
}
