package ai.devpath.sandbox.run;

import java.util.function.Consumer;

public interface RunnerBackend {

  RunResult run(RunSpec spec, Consumer<String> logCallback);

  boolean isAvailable();
}
