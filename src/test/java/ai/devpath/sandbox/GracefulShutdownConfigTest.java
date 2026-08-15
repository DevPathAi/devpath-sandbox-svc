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
  void executorFinishesBeforeSpringAndPodShutdownBudgets() {
    assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
        .isEqualTo("90s");
    assertThat(environment.getProperty(
        "devpath.sandbox.executor.drain-timeout-ms", Long.class)).isEqualTo(75_000L);
    assertThat(environment.getProperty(
        "spring.datasource.hikari.connection-timeout", Long.class)).isEqualTo(4_000L);
    assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.socketTimeout"))
        .isEqualTo("4");
    assertThat(environment.getProperty("spring.transaction.default-timeout")).isEqualTo("4s");
  }
}
