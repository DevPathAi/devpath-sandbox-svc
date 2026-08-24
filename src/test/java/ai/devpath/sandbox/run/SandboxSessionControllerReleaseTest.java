package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SandboxSessionControllerReleaseTest {
  @Test
  void ownerRecoveryRecordsTheRunBoundTerminalEvidence() {
    String candidate = "a".repeat(64);
    String runKey = "R".repeat(43);
    SandboxReleaseFaultRegistry release = new SandboxReleaseFaultRegistry(true);
    release.arm(candidate, runKey, "next-run-midstream-disconnect");
    release.arm(candidate, runKey, "next-run-truncated");
    SandboxReleaseFaultPlan plan = release.consumeForRun(candidate, runKey);
    plan.wrap(noOpDelivery()).session(72L);
    plan.recordTerminal(new SandboxTerminalEvent(72L, "COMPLETED", 0, true));

    SandboxSessionRepository sessions = mock(SandboxSessionRepository.class);
    SandboxSession session = mock(SandboxSession.class);
    when(session.getId()).thenReturn(72L);
    when(session.getUserId()).thenReturn(42L);
    when(session.getStatus()).thenReturn("COMPLETED");
    when(session.getExitCode()).thenReturn(0);
    when(session.getStdout()).thenReturn("partial");
    when(session.getStderr()).thenReturn("");
    when(session.isOutputTruncated()).thenReturn(true);
    when(session.getStartedAt()).thenReturn(Instant.now());
    when(sessions.findByIdAndUserId(72L, 42L)).thenReturn(Optional.of(session));
    Jwt jwt = mock(Jwt.class);
    when(jwt.getSubject()).thenReturn("42");

    new SandboxSessionController(sessions, release)
        .get(jwt, 72L, candidate, runKey);

    assertThat(release.checkpoint(candidate, runKey, "owner-recovery-truncated")).isTrue();
  }

  private static SandboxRunDelivery noOpDelivery() {
    return new SandboxRunDelivery() {
      @Override public void session(long sessionId) {}
      @Override public void log(String line) {}
      @Override public void result(SandboxTerminalEvent event) {}
      @Override public void complete() {}
    };
  }
}
