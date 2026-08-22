package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SandboxRecoveryContractTest {

  @Test
  void requestHasNoClientRunIdAndDocumentationDoesNotClaimPreHeaderRecovery() throws Exception {
    assertThat(Arrays.stream(SandboxRunRequest.class.getRecordComponents())
        .map(component -> component.getName()))
        .doesNotContain("clientRunId");

    String readme = Files.readString(Path.of("README.md"));
    assertThat(readme)
        .contains("X-Sandbox-Session-Id")
        .contains("X-Sandbox-Event-Version: 2")
        .contains("cannot be correlated")
        .contains("clientRunId");
  }
}
