package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SandboxOutputLimitsTest {

  @Test
  void capsCombinedOutputAt256KiBAndPersistsTruncation() {
    String stdout = "가".repeat(100_000);
    String stderr = "error".repeat(100_000);

    RunResult limited = SandboxOutputLimits.limit(new RunResult(
        SandboxTerminalStatus.FAILED, 1, stdout, stderr, 10L, 20, false));

    int bytes = limited.stdout().getBytes(StandardCharsets.UTF_8).length
        + limited.stderr().getBytes(StandardCharsets.UTF_8).length;
    assertThat(bytes).isLessThanOrEqualTo(256 * 1024);
    assertThat(limited.outputTruncated()).isTrue();
    assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(limited.stdout())).isTrue();
    assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(limited.stderr())).isTrue();
  }

  @Test
  void splitsEveryLogEventAt16KiBWithoutBreakingUtf8() {
    String output = "한글🙂".repeat(8_000);

    var chunks = SandboxOutputLimits.sseChunks(output);

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(chunk -> {
      assertThat(chunk.getBytes(StandardCharsets.UTF_8).length)
          .isLessThanOrEqualTo(16 * 1024);
      assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(chunk)).isTrue();
    });
    assertThat(String.join("", chunks)).isEqualTo(output);
  }
}
