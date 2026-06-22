package ai.devpath.sandbox.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class OutboxRelayScheduler {

  private final OutboxRelay outboxRelay;

  public OutboxRelayScheduler(OutboxRelay outboxRelay) {
    this.outboxRelay = outboxRelay;
  }

  @Scheduled(fixedDelay = 2000)
  public void relay() {
    outboxRelay.relayOnce();
  }
}
