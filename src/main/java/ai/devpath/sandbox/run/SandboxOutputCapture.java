package ai.devpath.sandbox.run;

import java.nio.charset.StandardCharsets;

/** Bounded in-run stdout/stderr capture with one shared UTF-8 byte budget. */
final class SandboxOutputCapture {

  private final int maxBytes;
  private final StringBuilder stdout = new StringBuilder();
  private final StringBuilder stderr = new StringBuilder();
  private int capturedBytes;
  private boolean truncated;

  SandboxOutputCapture() {
    this(SandboxOutputLimits.MAX_COMBINED_OUTPUT_BYTES);
  }

  SandboxOutputCapture(int maxBytes) {
    if (maxBytes < 0) {
      throw new IllegalArgumentException("Output byte limit cannot be negative");
    }
    this.maxBytes = maxBytes;
  }

  synchronized String appendStdout(String value) {
    return append(stdout, value);
  }

  synchronized String appendStderr(String value) {
    return append(stderr, value);
  }

  synchronized RunResult result(
      SandboxTerminalStatus status,
      int exitCode,
      Long cpuMsUsed,
      Integer memoryMbPeak) {
    return new RunResult(
        status,
        exitCode,
        stdout.toString(),
        stderr.toString(),
        cpuMsUsed,
        memoryMbPeak,
        truncated);
  }

  synchronized void markTruncated() {
    truncated = true;
  }

  private String append(StringBuilder target, String rawValue) {
    String value = rawValue == null ? "" : rawValue;
    int offset = 0;
    int acceptedEnd = 0;
    while (offset < value.length()) {
      int codePoint = value.codePointAt(offset);
      int charCount = Character.charCount(codePoint);
      int bytes = new String(Character.toChars(codePoint))
          .getBytes(StandardCharsets.UTF_8).length;
      if (capturedBytes + bytes > maxBytes) {
        truncated = true;
        break;
      }
      capturedBytes += bytes;
      offset += charCount;
      acceptedEnd = offset;
    }
    if (acceptedEnd < value.length()) {
      truncated = true;
    }
    String accepted = value.substring(0, acceptedEnd);
    target.append(accepted);
    return accepted;
  }
}
