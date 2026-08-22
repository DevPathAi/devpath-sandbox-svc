package ai.devpath.sandbox.run;

import java.time.Instant;

public record SandboxSessionRecoveryView(
    Long sessionId,
    String language,
    Long contentId,
    Long codeBlockId,
    String stdout,
    String stderr,
    Integer exitCode,
    String status,
    boolean truncated,
    Instant startedAt,
    Instant finishedAt) {

  static SandboxSessionRecoveryView from(SandboxSession session) {
    return new SandboxSessionRecoveryView(
        session.getId(),
        session.getLanguage(),
        session.getContentId(),
        session.getCodeBlockId(),
        session.getStdout(),
        session.getStderr(),
        session.getExitCode(),
        session.getStatus(),
        session.isOutputTruncated(),
        session.getStartedAt(),
        session.getFinishedAt());
  }
}
