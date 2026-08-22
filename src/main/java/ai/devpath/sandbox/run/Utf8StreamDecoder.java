package ai.devpath.sandbox.run;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Stateful UTF-8 decoder that retains an incomplete code point across Docker frames. */
final class Utf8StreamDecoder {

  private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPLACE)
      .onUnmappableCharacter(CodingErrorAction.REPLACE);
  private byte[] carry = new byte[0];
  private boolean finished;

  synchronized String decode(byte[] payload) {
    if (finished) {
      throw new IllegalStateException("UTF-8 stream is already finished");
    }
    byte[] safePayload = payload == null ? new byte[0] : payload;
    byte[] combined = Arrays.copyOf(carry, carry.length + safePayload.length);
    System.arraycopy(safePayload, 0, combined, carry.length, safePayload.length);
    ByteBuffer input = ByteBuffer.wrap(combined);
    CharBuffer output = CharBuffer.allocate(Math.max(1, combined.length + 1));
    decoder.decode(input, output, false);
    carry = new byte[input.remaining()];
    input.get(carry);
    output.flip();
    return output.toString();
  }

  synchronized String finish() {
    if (finished) {
      return "";
    }
    finished = true;
    ByteBuffer input = ByteBuffer.wrap(carry);
    CharBuffer output = CharBuffer.allocate(Math.max(2, carry.length + 2));
    decoder.decode(input, output, true);
    decoder.flush(output);
    carry = new byte[0];
    output.flip();
    return output.toString();
  }
}
