package ai.devpath.sandbox.outbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
    partitions = 1,
    topics = {"sandbox.run.submitted"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class OutboxRelayTest {

  @Autowired OutboxRepository outbox;
  @Autowired OutboxRelay relay;
  @Autowired ConsumerFactory<String, String> cf;
  @Autowired EmbeddedKafkaBroker broker;

  @Test
  void relayPublishesUnpublishedRowAndMarksPublished() {
    OutboxEntry e = new OutboxEntry();
    e.setAggregateType("sandbox_session");
    e.setAggregateId("999");
    e.setEventType("sandbox.run.submitted");
    e.setPayload("{\"sandboxSessionId\":999}");
    e.setCreatedAt(Instant.now());
    Long id = outbox.save(e).getId();

    int published = relay.relayOnce();
    assertTrue(published >= 1, "최소 1건 발행");
    assertNotNull(outbox.findById(id).orElseThrow().getPublishedAt(), "published_at 설정");

    try (Consumer<String, String> c = cf.createConsumer("relay-grp", "relay")) {
      broker.consumeFromAnEmbeddedTopic(c, "sandbox.run.submitted");
      ConsumerRecords<String, String> recs = KafkaTestUtils.getRecords(c);
      boolean found = StreamSupport.stream(recs.spliterator(), false)
          .anyMatch(r -> r.value().contains("999"));
      assertTrue(found, "발행된 레코드에 sandboxSessionId=999 포함");
    }
  }
}
