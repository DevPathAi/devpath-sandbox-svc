package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.sandbox.outbox.OutboxRepository;
import ai.devpath.shared.event.SandboxRunSubmittedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SandboxRunEventPublisherTest {

  @Autowired SandboxRunEventPublisher publisher;
  @Autowired OutboxRepository outbox;

  @Test
  void publishSubmittedWritesOutboxEntry() {
    publisher.publishSubmitted(123L, 42L, "PYTHON", 77L);

    var entry = outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc().stream()
        .filter(e -> "123".equals(e.getAggregateId()))
        .findFirst()
        .orElseThrow();

    assertThat(entry.getAggregateType()).isEqualTo("sandbox_session");
    assertThat(entry.getEventType()).isEqualTo(SandboxRunSubmittedEvent.EVENT_TYPE);
    assertThat(entry.getPayload()).contains("\"sandboxSessionId\": 123");
    assertThat(entry.getPayload()).contains("\"userId\": 42");
    assertThat(entry.getPayload()).contains("\"language\": \"PYTHON\"");
    assertThat(entry.getPayload()).contains("\"contentId\": 77");
  }
}
