package ai.devpath.sandbox.run;

public class SandboxUnavailableException extends RuntimeException {

  public SandboxUnavailableException(String message) {
    super(message);
  }

  public SandboxUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
