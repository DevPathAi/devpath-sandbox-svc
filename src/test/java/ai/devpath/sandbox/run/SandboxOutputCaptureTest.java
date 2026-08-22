package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SandboxOutputCaptureTest {

  @Test
  void tenThousandFrameOutputStormNeverGrowsPastCombinedLimit() {
    SandboxOutputCapture capture = new SandboxOutputCapture();
    String frame = "0123456789abcdef".repeat(8);

    for (int i = 0; i < 10_000; i++) {
      if (i % 2 == 0) {
        capture.appendStdout(frame);
      } else {
        capture.appendStderr(frame);
      }
    }

    RunResult result = capture.result(SandboxTerminalStatus.COMPLETED, 0, null, null);
    int persistedBytes = result.stdout().getBytes(StandardCharsets.UTF_8).length
        + result.stderr().getBytes(StandardCharsets.UTF_8).length;
    assertThat(persistedBytes).isEqualTo(256 * 1024);
    assertThat(result.outputTruncated()).isTrue();
  }

  @Test
  void utf8BoundaryRemainsValidWhenLastFrameIsPartiallyAccepted() {
    SandboxOutputCapture capture = new SandboxOutputCapture(5);

    capture.appendStdout("가나다");

    RunResult result = capture.result(SandboxTerminalStatus.FAILED, 1, null, null);
    assertThat(result.stdout()).isEqualTo("가");
    assertThat(result.stdout().getBytes(StandardCharsets.UTF_8)).hasSize(3);
    assertThat(result.outputTruncated()).isTrue();
  }
}
