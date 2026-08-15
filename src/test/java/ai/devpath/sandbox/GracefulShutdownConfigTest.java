package ai.devpath.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class GracefulShutdownConfigTest {

  @Autowired Environment environment;

  @Test
  void springAndExecutorDrainBudgetsAreAtLeastNinetySeconds() {
    assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
        .isEqualTo("90s");
    assertThat(environment.getProperty(
        "devpath.sandbox.executor.drain-timeout-ms", Long.class)).isGreaterThanOrEqualTo(90_000L);
  }
}
