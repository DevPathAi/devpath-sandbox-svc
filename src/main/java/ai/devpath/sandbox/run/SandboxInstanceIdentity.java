package ai.devpath.sandbox.run;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A process-incarnation identity. A pod restart must never inherit the prior process lease. */
@Component
final class SandboxInstanceIdentity {

  private final String value;

  @Autowired
  SandboxInstanceIdentity(@Value("${devpath.sandbox.pod-name}") String podName) {
    this(podName, UUID.randomUUID());
  }

  SandboxInstanceIdentity(String podName, UUID startupId) {
    String normalizedPodName = podName == null ? "" : podName.trim();
    if (normalizedPodName.isBlank()) {
      throw new IllegalArgumentException("Sandbox pod name must not be blank");
    }
    if (startupId == null) {
      throw new IllegalArgumentException("Sandbox startup id must not be null");
    }
    this.value = normalizedPodName + ":" + startupId;
  }

  String value() {
    return value;
  }
}
