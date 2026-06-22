package ai.devpath.sandbox.run;

import java.util.function.Consumer;

class UnavailableRunnerBackend implements RunnerBackend {

  @Override
  public RunResult run(RunSpec spec, Consumer<String> logCallback) {
    throw new SandboxUnavailableException("Sandbox runner backend is not configured");
  }
}
