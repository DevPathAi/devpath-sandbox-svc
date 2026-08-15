package ai.devpath.sandbox.run;

/** Best-effort delivery surface. Implementations must not own or cancel execution state. */
public interface SandboxRunDelivery {

  void session(long sessionId);

  void log(String line);

  void result(SandboxTerminalEvent event);

  void complete();

  default void heartbeat() {}
}
