package ai.devpath.sandbox.run;

import ai.devpath.sandbox.outbox.OutboxEntry;
import ai.devpath.sandbox.outbox.OutboxRepository;
import ai.devpath.shared.event.SandboxRunSubmittedEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SandboxRunEventPublisher {

  private final OutboxRepository outbox;
  private final JsonMapper jsonMapper;

  public SandboxRunEventPublisher(OutboxRepository outbox, JsonMapper jsonMapper) {
    this.outbox = outbox;
    this.jsonMapper = jsonMapper;
  }

  public void publishSubmitted(long sandboxSessionId, long userId, String language, Long contentId) {
    var event = new SandboxRunSubmittedEvent(
        UUID.randomUUID(), Instant.now(), userId, sandboxSessionId, language, contentId);
    OutboxEntry entry = new OutboxEntry();
    entry.setAggregateType("sandbox_session");
    entry.setAggregateId(String.valueOf(sandboxSessionId));
    entry.setEventType(SandboxRunSubmittedEvent.EVENT_TYPE);
    entry.setPayload(serialize(event));
    entry.setCreatedAt(Instant.now());
    outbox.save(entry);
  }

  private String serialize(SandboxRunSubmittedEvent event) {
    try {
      return jsonMapper.writeValueAsString(event);
    } catch (Exception e) {
      throw new IllegalStateException("SandboxRunSubmittedEvent 직렬화 실패", e);
    }
  }
}
