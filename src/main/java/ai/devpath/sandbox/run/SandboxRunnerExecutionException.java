package ai.devpath.sandbox.run;

/** Runner failure after execution started, carrying the exact bounded output captured so far. */
final class SandboxRunnerExecutionException extends SandboxUnavailableException {

  private final RunResult result;

  SandboxRunnerExecutionException(String message, RunResult result) {
    super(message);
    this.result = requireResult(result);
  }

  SandboxRunnerExecutionException(String message, Throwable cause, RunResult result) {
    super(message, cause);
    this.result = requireResult(result);
  }

  RunResult result() {
    return result;
  }

  private static RunResult requireResult(RunResult result) {
    if (result == null) {
      throw new IllegalArgumentException("Captured runner result must not be null");
    }
    return result;
  }
}
