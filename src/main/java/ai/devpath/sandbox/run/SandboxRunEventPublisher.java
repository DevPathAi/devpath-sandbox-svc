package ai.devpath.sandbox.run;

import ai.devpath.sandbox.outbox.OutboxRepository;
import ai.devpath.shared.event.SandboxRunSubmittedEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SandboxRunEventPublisher {

  private final OutboxRepository outbox;
  private final JsonMapper jsonMapper;

  public SandboxRunEventPublisher(OutboxRepository outbox, JsonMapper jsonMapper) {
    this.outbox = outbox;
    this.jsonMapper = jsonMapper;
  }

  @Transactional
  public boolean publishSubmitted(
      long sandboxSessionId, long userId, String language, Long contentId) {
    var event = new SandboxRunSubmittedEvent(
        UUID.randomUUID(), Instant.now(), userId, sandboxSessionId, language, contentId);
    String dedupeKey = SandboxRunSubmittedEvent.EVENT_TYPE + ":" + sandboxSessionId;
    return outbox.insertSandboxTerminalOnce(
        String.valueOf(sandboxSessionId),
        SandboxRunSubmittedEvent.EVENT_TYPE,
        serialize(event),
        Instant.now(),
        dedupeKey) == 1;
  }

  public boolean terminalEventExists(long sandboxSessionId) {
    return outbox.existsSandboxTerminal(String.valueOf(sandboxSessionId));
  }

  private String serialize(SandboxRunSubmittedEvent event) {
    try {
      return jsonMapper.writeValueAsString(event);
    } catch (Exception e) {
      throw new IllegalStateException("SandboxRunSubmittedEvent 직렬화 실패", e);
    }
  }
}
