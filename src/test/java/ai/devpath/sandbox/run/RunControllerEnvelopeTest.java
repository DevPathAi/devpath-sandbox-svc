package ai.devpath.sandbox.run;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
@Import({SecurityConfig.class, ApiExceptionHandler.class, SandboxHeartbeatScheduler.class})
class RunControllerEnvelopeTest {

  @Autowired MockMvc mvc;
  @MockitoBean SandboxRunService runService;
  @MockitoBean SandboxReleaseFaultRegistry releaseFaults;

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

  @Test
  void boundedAdmissionReturnsSandboxBusy429BeforeStreaming() throws Exception {
    when(runService.isRunnerAvailable()).thenReturn(true);
    when(runService.start(org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any()))
        .thenThrow(new SandboxBusyException("Sandbox capacity is full"));

    mvc.perform(post("/sandbox/run").with(user("42"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code").value("SANDBOX_BUSY"));
  }

  @Test
  void browserRunHeadersAreBoundToTheSelectedReleasePlan() throws Exception {
    when(runService.isRunnerAvailable()).thenReturn(true);
    SandboxReleaseFaultPlan plan = org.mockito.Mockito.mock(SandboxReleaseFaultPlan.class);
    when(releaseFaults.consumeForRun("a".repeat(64), "R".repeat(43))).thenReturn(plan);
    when(runService.start(
        org.mockito.ArgumentMatchers.eq(42L),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.same(plan)))
        .thenReturn(new AcceptedSandboxRun(73L));

    mvc.perform(post("/sandbox/run").with(user("42"))
            .header("X-Candidate-Spec-Sha256", "a".repeat(64))
            .header("X-Release-Run-Key", "R".repeat(43))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(request().asyncStarted());

    org.mockito.Mockito.verify(releaseFaults)
        .consumeForRun("a".repeat(64), "R".repeat(43));
  }
}
