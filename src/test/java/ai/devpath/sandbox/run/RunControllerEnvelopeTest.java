package ai.devpath.sandbox.run;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.sandbox.config.SecurityConfig;
import ai.devpath.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 스펙 §3.4 공통 에러 envelope 검증(공용 ApiExceptionHandler). DB-free 슬라이스.
 * SandboxUnavailable은 스트림 개시 전 동기 throw(RunController)라 advice가 503 envelope를 낸다.
 */
@WebMvcTest(RunController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class RunControllerEnvelopeTest {

  @Autowired MockMvc mvc;
  @MockitoBean SandboxRunService runService;

  private static RequestPostProcessor user(String sub) {
    return jwt().jwt(j -> j.subject(sub));
  }

  @Test
  void runnerUnavailable_returns_sandbox_unavailable_envelope() throws Exception {
    when(runService.isRunnerAvailable()).thenReturn(false);

    mvc.perform(post("/sandbox/run").with(user("42"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("SANDBOX_UNAVAILABLE"));
  }

  @Test
  void invalidRequest_returns_validation_failed_envelope() throws Exception {
    // language 누락 → validate()가 IllegalArgumentException → 공용 advice VALIDATION_FAILED(400).
    mvc.perform(post("/sandbox/run").with(user("42"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
  }
}
