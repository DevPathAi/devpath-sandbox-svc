package ai.devpath.sandbox.run;

public record RunResult(
    int exitCode,
    String stdout,
    String stderr,
    Long cpuMsUsed,
    Integer memoryMbPeak
) {
}
