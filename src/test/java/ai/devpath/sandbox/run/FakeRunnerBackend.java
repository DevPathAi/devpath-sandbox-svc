package ai.devpath.sandbox.run;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

class FakeRunnerBackend implements RunnerBackend {

  private RunResult result = new RunResult(0, "", "", null, null);
  private RuntimeException failure;
  private final List<String> logs = new ArrayList<>();
  private RunSpec lastSpec;

  void result(RunResult result) {
    this.result = result;
  }

  void fail(RuntimeException failure) {
    this.failure = failure;
  }

  void logs(String... values) {
    logs.clear();
    logs.addAll(List.of(values));
  }

  RunSpec lastSpec() {
    return lastSpec;
  }

  @Override
  public RunResult run(RunSpec spec, Consumer<String> logCallback) {
    lastSpec = spec;
    if (failure != null) {
      throw failure;
    }
    logs.forEach(logCallback);
    return result;
  }
}
