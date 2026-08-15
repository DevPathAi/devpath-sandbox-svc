package ai.devpath.sandbox.run;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SandboxOutputLimits {

  static final int MAX_COMBINED_OUTPUT_BYTES = 256 * 1024;
  static final int MAX_SSE_EVENT_BYTES = 16 * 1024;

  private SandboxOutputLimits() {}

  static RunResult limit(RunResult result) {
    Slice stdout = slice(result.stdout(), MAX_COMBINED_OUTPUT_BYTES);
    int remaining = MAX_COMBINED_OUTPUT_BYTES - stdout.bytes();
    Slice stderr = slice(result.stderr(), remaining);
    boolean truncated = result.outputTruncated() || stdout.truncated() || stderr.truncated();
    return new RunResult(
        result.terminalStatus(),
        result.exitCode(),
        stdout.value(),
        stderr.value(),
        result.cpuMsUsed(),
        result.memoryMbPeak(),
        truncated);
  }

  static List<String> sseChunks(String value) {
    String safe = value == null ? "" : value;
    List<String> chunks = new ArrayList<>();
    int offset = 0;
    while (offset < safe.length()) {
      int end = offset;
      int bytes = 0;
      while (end < safe.length()) {
        int codePoint = safe.codePointAt(end);
        int charCount = Character.charCount(codePoint);
        int codePointBytes = new String(Character.toChars(codePoint))
            .getBytes(StandardCharsets.UTF_8).length;
        if (bytes + codePointBytes > MAX_SSE_EVENT_BYTES) {
          break;
        }
        bytes += codePointBytes;
        end += charCount;
      }
      if (end == offset) {
        throw new IllegalStateException("SSE byte limit cannot fit a UTF-8 code point");
      }
      chunks.add(safe.substring(offset, end));
      offset = end;
    }
    return chunks;
  }

  private static Slice slice(String value, int byteBudget) {
    String safe = value == null ? "" : value;
    if (safe.isEmpty()) {
      return new Slice("", 0, false);
    }
    int offset = 0;
    int bytes = 0;
    while (offset < safe.length()) {
      int codePoint = safe.codePointAt(offset);
      int charCount = Character.charCount(codePoint);
      int codePointBytes = new String(Character.toChars(codePoint))
          .getBytes(StandardCharsets.UTF_8).length;
      if (bytes + codePointBytes > byteBudget) {
        break;
      }
      bytes += codePointBytes;
      offset += charCount;
    }
    return new Slice(safe.substring(0, offset), bytes, offset < safe.length());
  }

  private record Slice(String value, int bytes, boolean truncated) {}
}
