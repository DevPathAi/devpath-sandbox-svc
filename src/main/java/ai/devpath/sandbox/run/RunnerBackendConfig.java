package ai.devpath.sandbox.run;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RunnerBackendConfig {

  @Bean
  @ConditionalOnMissingBean(RunnerBackend.class)
  RunnerBackend unavailableRunnerBackend() {
    return new UnavailableRunnerBackend();
  }
}
