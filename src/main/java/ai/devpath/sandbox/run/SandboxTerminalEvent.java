package ai.devpath.sandbox.run;

public record SandboxTerminalEvent(
    long sessionId,
    String status,
    Integer exitCode,
    boolean truncated) {

  static SandboxTerminalEvent from(SandboxSession session) {
    return new SandboxTerminalEvent(
        session.getId(),
        session.getStatus(),
        session.getExitCode(),
        session.isOutputTruncated());
  }
}
