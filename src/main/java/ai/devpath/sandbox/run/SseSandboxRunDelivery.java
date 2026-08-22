package ai.devpath.sandbox.run;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Bounded asynchronous SSE delivery. A slow or failed socket can drop delivery frames but cannot
 * block, cancel, or relabel the execution state machine.
 */
final class SseSandboxRunDelivery implements SandboxRunDelivery {

  private static final int DELIVERY_QUEUE_CAPACITY = 20;

  private final SseEmitter emitter;
  private final boolean terminalEventsEnabled;
  private final BlockingQueue<DeliveryFrame> frames =
      new ArrayBlockingQueue<>(DELIVERY_QUEUE_CAPACITY);
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object enqueueLock = new Object();

  SseSandboxRunDelivery(SseEmitter emitter, boolean terminalEventsEnabled) {
    this.emitter = emitter;
    this.terminalEventsEnabled = terminalEventsEnabled;
    Thread.ofVirtual().name("sandbox-sse-delivery").start(this::deliverFrames);
  }

  @Override
  public void session(long sessionId) {
    enqueueControl(new DeliveryFrame(
        SseEmitter.event().name("session").data(String.valueOf(sessionId)), false, false));
  }

  @Override
  public void log(String line) {
    for (String chunk : SandboxOutputLimits.sseChunks(line)) {
      if (!enqueueBestEffort(new DeliveryFrame(
          SseEmitter.event().name("log").data(chunk), true, false))) {
        return;
      }
    }
  }

  @Override
  public void result(SandboxTerminalEvent event) {
    if (terminalEventsEnabled) {
      enqueueControl(new DeliveryFrame(
          SseEmitter.event().name("result").data(event), false, false));
    }
  }

  @Override
  public void heartbeat() {
    enqueueBestEffort(new DeliveryFrame(SseEmitter.event().comment("heartbeat"), true, false));
  }

  @Override
  public void complete() {
    synchronized (enqueueLock) {
      if (!accepting.getAndSet(false)) {
        return;
      }
      enqueueControlLocked(DeliveryFrame.completeFrame());
    }
  }

  private boolean enqueueBestEffort(DeliveryFrame frame) {
    if (!accepting.get() || closed.get()) {
      return false;
    }
    return frames.offer(frame);
  }

  private void enqueueControl(DeliveryFrame frame) {
    synchronized (enqueueLock) {
      if (!accepting.get() || closed.get()) {
        return;
      }
      enqueueControlLocked(frame);
    }
  }

  private void enqueueControlLocked(DeliveryFrame frame) {
    if (frames.offer(frame)) {
      return;
    }
    frames.removeIf(DeliveryFrame::droppable);
    frames.offer(frame);
  }

  private void deliverFrames() {
    try {
      while (!closed.get()) {
        DeliveryFrame frame = frames.take();
        if (frame.completion()) {
          completeEmitter();
          return;
        }
        emitter.send(frame.event());
      }
    } catch (IOException | RuntimeException ignored) {
      closed.set(true);
      accepting.set(false);
      frames.clear();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      closed.set(true);
      accepting.set(false);
      frames.clear();
    }
  }

  private void completeEmitter() {
    try {
      emitter.complete();
    } catch (RuntimeException ignored) {
      // Completion is best-effort like every other delivery operation.
    } finally {
      closed.set(true);
      frames.clear();
    }
  }

  private record DeliveryFrame(
      SseEmitter.SseEventBuilder event,
      boolean droppable,
      boolean completion) {

    private static DeliveryFrame completeFrame() {
      return new DeliveryFrame(null, false, true);
    }
  }
}
