package ai.devpath.sandbox.run;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Creates synthetic expired rows and invokes the production two-pass reconciler. */
@Service
@ConditionalOnProperty(name = "devpath.release.enabled", havingValue = "true")
public class SandboxReleaseStaleFixtureService {
  public record Seeded(long sessionId, boolean passed) {}

  private final SandboxSessionRepository sessions;
  private final SandboxRunPersistenceService persistence;
  private final SandboxReleaseFaultRegistry release;

  public SandboxReleaseStaleFixtureService(
      SandboxSessionRepository sessions,
      SandboxRunPersistenceService persistence,
      SandboxReleaseFaultRegistry release) {
    this.sessions = sessions;
    this.persistence = persistence;
    this.release = release;
  }

  public Seeded seed(
      String candidate,
      String runKey,
      long userId,
      String status) {
    if (userId < 1 || userId > 9_007_199_254_740_991L) {
      throw new IllegalArgumentException("release fixture user id is invalid");
    }
    String expectedTerminal = switch (status) {
      case "ALLOCATING" -> "FAILED";
      case "RUNNING" -> "KILLED";
      default -> throw new IllegalArgumentException("unsupported stale Sandbox status");
    };

    Instant seededAt = Instant.now();
    SandboxSession fixture = new SandboxSession();
    fixture.setUserId(userId);
    fixture.setLanguage("PYTHON");
    fixture.setSubmittedCode("# mission-spine synthetic stale fixture");
    fixture.setStatus(status);
    fixture.setOwnerInstance("mission-spine-dead-instance");
    fixture.setStartedAt(seededAt.minusSeconds(60));
    fixture.setUpdatedAt(seededAt.minusSeconds(60));
    fixture.setLeaseExpiresAt(seededAt.minusSeconds(1));
    long sessionId = sessions.saveAndFlush(fixture).getId();

    persistence.reconcileExpired(
        seededAt, seededAt.minusSeconds(35), 100);
    persistence.reconcileExpired(
        seededAt.plusSeconds(31), seededAt.minusSeconds(35), 100);
    boolean passed = sessions.findById(sessionId)
        .map(value -> expectedTerminal.equals(value.getStatus())
            && "RECONCILER".equals(value.getTerminalSource()))
        .orElse(false);
    release.recordStaleReconciliation(candidate, runKey, status, passed);
    return new Seeded(sessionId, passed);
  }
}
