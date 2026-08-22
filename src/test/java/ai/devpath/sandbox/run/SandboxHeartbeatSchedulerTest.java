package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SandboxHeartbeatSchedulerTest {

  @Test
  void emitsCommentsWhileRunningAndStopsAfterCompletion() throws Exception {
    SandboxHeartbeatScheduler scheduler = new SandboxHeartbeatScheduler(Duration.ofMillis(5));
    CountDownLatch heartbeats = new CountDownLatch(2);
    SandboxRunDelivery wrapped = scheduler.wrap(new SandboxRunDelivery() {
      @Override public void session(long sessionId) {}
      @Override public void log(String line) {}
      @Override public void result(SandboxTerminalEvent event) {}
      @Override public void heartbeat() { heartbeats.countDown(); }
      @Override public void complete() {}
    });

    assertThat(heartbeats.await(1, TimeUnit.SECONDS)).isTrue();
    wrapped.complete();
    long remaining = heartbeats.getCount();
    Thread.sleep(30L);
    assertThat(heartbeats.getCount()).isEqualTo(remaining);
    scheduler.close();
  }
}
