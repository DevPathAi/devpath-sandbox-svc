package ai.devpath.sandbox.run;

import java.util.function.Consumer;
import java.time.Instant;

public interface RunnerBackend {

  RunResult run(RunSpec spec, Consumer<String> logCallback);

  default RunResult run(
      RunSpec spec,
      Consumer<String> logCallback,
      Consumer<String> containerCreated) {
    return run(spec, logCallback);
  }

  boolean isAvailable();

  default int reapExpiredContainers(Instant now) {
    return 0;
  }
}
