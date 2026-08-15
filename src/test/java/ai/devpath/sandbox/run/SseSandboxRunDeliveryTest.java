package ai.devpath.sandbox.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseSandboxRunDeliveryTest {

  @Test
  void emitterFailureNeverEscapesIntoExecutionAndStopsFurtherWrites() throws Exception {
    SseEmitter emitter = mock(SseEmitter.class);
    doThrow(new IOException("client disconnected"))
        .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    SseSandboxRunDelivery delivery = new SseSandboxRunDelivery(emitter, true);

    assertDoesNotThrow(() -> delivery.log("private user output"));
    assertDoesNotThrow(() -> delivery.session(10L));
    assertDoesNotThrow(() -> delivery.result(
        new SandboxTerminalEvent(10L, "COMPLETED", 0, false)));
    assertDoesNotThrow(delivery::complete);

    verify(emitter, org.mockito.Mockito.timeout(1_000))
        .send(any(SseEmitter.SseEventBuilder.class));
    verifyNoMoreInteractions(emitter);
  }

  @Test
  void slowClientCannotBlockExecutionSideLogCallbacks() throws Exception {
    SseEmitter emitter = mock(SseEmitter.class);
    CountDownLatch sendStarted = new CountDownLatch(1);
    CountDownLatch releaseSend = new CountDownLatch(1);
    AtomicBoolean firstSend = new AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(inv -> {
      sendStarted.countDown();
      if (firstSend.compareAndSet(true, false)) {
        releaseSend.await(2, TimeUnit.SECONDS);
      }
      return null;
    }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    SseSandboxRunDelivery delivery = new SseSandboxRunDelivery(emitter, true);
    Thread releaser = new Thread(() -> {
      try {
        Thread.sleep(500L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      releaseSend.countDown();
    });
    releaser.start();

    long startedAt = System.nanoTime();
    delivery.log("first");
    assertThat(sendStarted.await(1, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 10_000; i++) {
      delivery.log("frame-" + i);
    }
    long callbackMillis = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(callbackMillis).isLessThan(250L);
    delivery.result(new SandboxTerminalEvent(12L, "COMPLETED", 0, true));
    delivery.complete();
    releaser.join();
  }

  @Test
  void saturatedLogQueueStillDeliversResultAndCompletionControlFrames() throws Exception {
    SseEmitter emitter = mock(SseEmitter.class);
    CountDownLatch firstSendStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstSend = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(invocation -> {
      if (first.compareAndSet(true, false)) {
        firstSendStarted.countDown();
        releaseFirstSend.await(2, TimeUnit.SECONDS);
      }
      return null;
    }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    SseSandboxRunDelivery delivery = new SseSandboxRunDelivery(emitter, true);
    delivery.session(44L);
    assertThat(firstSendStarted.await(1, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 10_000; i++) {
      delivery.log("saturated-" + i);
    }

    delivery.result(new SandboxTerminalEvent(44L, "COMPLETED", 0, true));
    delivery.complete();
    releaseFirstSend.countDown();

    verify(emitter, org.mockito.Mockito.timeout(1_000).times(2))
        .send(any(SseEmitter.SseEventBuilder.class));
    verify(emitter, org.mockito.Mockito.timeout(1_000)).complete();
  }
}
