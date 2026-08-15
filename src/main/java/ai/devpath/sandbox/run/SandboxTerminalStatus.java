package ai.devpath.sandbox.run;

/** Durable terminal outcomes. Delivery and admission failures are deliberately not statuses. */
public enum SandboxTerminalStatus {
  COMPLETED,
  FAILED,
  KILLED,
  TIMED_OUT;

  static SandboxTerminalStatus fromLegacyExitCode(int exitCode) {
    if (exitCode == 0) {
      return COMPLETED;
    }
    if (exitCode == -1) {
      return KILLED;
    }
    return FAILED;
  }
}
