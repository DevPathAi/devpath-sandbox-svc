package ai.devpath.sandbox.run;

public record RunResult(
    SandboxTerminalStatus terminalStatus,
    int exitCode,
    String stdout,
    String stderr,
    Long cpuMsUsed,
    Integer memoryMbPeak,
    boolean outputTruncated
) {
  public RunResult(int exitCode, String stdout, String stderr, Long cpuMsUsed,
      Integer memoryMbPeak) {
    this(SandboxTerminalStatus.fromLegacyExitCode(exitCode), exitCode, stdout, stderr,
        cpuMsUsed, memoryMbPeak, false);
  }

  public static RunResult timedOut(String stdout, String stderr) {
    return new RunResult(SandboxTerminalStatus.TIMED_OUT, -1, stdout, stderr,
        null, null, false);
  }
}
