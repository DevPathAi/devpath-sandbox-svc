package ai.devpath.sandbox.run;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.util.function.Consumer;

/** Serializes Docker callbacks with decoder shutdown and retains a bounded exact result. */
final class DockerLogCapture {

  private final Consumer<String> logCallback;
  private final SandboxOutputCapture output = new SandboxOutputCapture();
  private final Utf8StreamDecoder stdoutDecoder = new Utf8StreamDecoder();
  private final Utf8StreamDecoder stderrDecoder = new Utf8StreamDecoder();
  private boolean finished;

  DockerLogCapture(Consumer<String> logCallback) {
    this.logCallback = logCallback;
  }

  synchronized void onFrame(Frame frame) {
    if (finished) {
      output.markTruncated();
      return;
    }
    boolean stderr = frame.getStreamType() == StreamType.STDERR;
    String chunk = (stderr ? stderrDecoder : stdoutDecoder).decode(frame.getPayload());
    appendDecoded(chunk, stderr);
  }

  synchronized void finish(boolean streamCompleted) {
    if (finished) {
      if (!streamCompleted) {
        output.markTruncated();
      }
      return;
    }
    finished = true;
    if (!streamCompleted) {
      output.markTruncated();
    }
    appendDecoded(stdoutDecoder.finish(), false);
    appendDecoded(stderrDecoder.finish(), true);
  }

  synchronized String appendStderr(String value) {
    String accepted = output.appendStderr(value);
    deliver(accepted);
    return accepted;
  }

  synchronized RunResult result(
      SandboxTerminalStatus status,
      int exitCode,
      Long cpuMsUsed,
      Integer memoryMbPeak) {
    return output.result(status, exitCode, cpuMsUsed, memoryMbPeak);
  }

  private void appendDecoded(String chunk, boolean stderr) {
    String accepted = stderr ? output.appendStderr(chunk) : output.appendStdout(chunk);
    deliver(accepted);
  }

  private void deliver(String accepted) {
    String line = accepted.stripTrailing();
    if (!line.isEmpty()) {
      logCallback.accept(line);
    }
  }
}
