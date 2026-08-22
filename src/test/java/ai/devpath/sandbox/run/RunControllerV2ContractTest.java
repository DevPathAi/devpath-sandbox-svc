package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

@WebMvcTest(RunController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, SandboxHeartbeatScheduler.class})
class RunControllerV2ContractTest {

  @Autowired MockMvc mvc;
  @MockitoBean SandboxRunService runService;

  @Test
  void acceptedRunExposesHeaderAndEarlyNumericSessionThenV2TerminalResult() throws Exception {
    when(runService.isRunnerAvailable()).thenReturn(true);
    doAnswer(inv -> {
      SandboxRunDelivery delivery = inv.getArgument(2);
      delivery.session(99L);
      delivery.log("hello");
      delivery.result(new SandboxTerminalEvent(99L, "TIMED_OUT", -1, true));
      delivery.complete();
      return new AcceptedSandboxRun(99L);
    }).when(runService).start(anyLong(), any(), any());

    long startedAt = System.nanoTime();
    var result = mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .header("X-Sandbox-Event-Version", "2")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(request().asyncStarted())
        .andExpect(header().string("X-Sandbox-Session-Id", "99"))
        .andExpect(header().string("Access-Control-Expose-Headers", "X-Sandbox-Session-Id"))
        .andReturn();
    long acceptedMillis = (System.nanoTime() - startedAt) / 1_000_000;

    String body = mvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(acceptedMillis).isLessThan(1_000L);
    assertThat(body).contains("event:session", "data:99", "event:log", "data:hello");
    assertThat(body).contains("event:result", "\"sessionId\":99", "\"status\":\"TIMED_OUT\"");
    assertThat(body.indexOf("event:session")).isLessThan(body.indexOf("event:log"));
  }

  @Test
  void legacyConsumerKeepsNumericSessionAndDoesNotReceiveResultJsonAsLog() throws Exception {
    when(runService.isRunnerAvailable()).thenReturn(true);
    doAnswer(inv -> {
      SandboxRunDelivery delivery = inv.getArgument(2);
      delivery.session(100L);
      delivery.log("legacy-safe");
      delivery.result(new SandboxTerminalEvent(100L, "COMPLETED", 0, false));
      delivery.complete();
      return new AcceptedSandboxRun(100L);
    }).when(runService).start(anyLong(), any(), any());

    var result = mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(request().asyncStarted())
        .andExpect(header().string("X-Sandbox-Session-Id", "100"))
        .andReturn();

    String body = mvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(body).contains("event:session", "data:100", "data:legacy-safe");
    assertThat(body).doesNotContain("event:result", "\"status\":\"COMPLETED\"");
  }
}
