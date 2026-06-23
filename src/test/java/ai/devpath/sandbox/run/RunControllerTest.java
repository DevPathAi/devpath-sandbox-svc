package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean SandboxRunService sandboxRunService;

  @Test
  void unauthenticatedRequestReturns401() throws Exception {
    mvc.perform(post("/sandbox/run")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validRequestReturnsSseStreamWithLogEvents() throws Exception {
    doReturn(true).when(sandboxRunService).isRunnerAvailable();
    doAnswer(inv -> {
      java.util.function.Consumer<String> cb = inv.getArgument(2);
      cb.accept("Hello, World!");
      SandboxSession s = new SandboxSession();
      s.setStatus("COMPLETED");
      return s;
    }).when(sandboxRunService).execute(anyLong(), any(), any());

    var result = mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print('Hello')\",\"language\":\"PYTHON\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    String sse = mvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andReturn().getResponse().getContentAsString();

    assertThat(sse).contains("event:log");
    assertThat(sse).contains("data:Hello, World!");
  }

  @Test
  void runnerUnavailableReturns503WithoutStreaming() throws Exception {
    doReturn(false).when(sandboxRunService).isRunnerAvailable();

    mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isServiceUnavailable());

    // SSE 시작 전 차단이므로 실행 자체가 호출되지 않는다(세션·이벤트 찌꺼기 방지).
    verify(sandboxRunService, never()).execute(anyLong(), any(), any());
  }

  @Test
  void runtimeUnavailableDuringExecuteReturns503() throws Exception {
    doReturn(true).when(sandboxRunService).isRunnerAvailable();
    doThrow(new SandboxUnavailableException("Docker 미가동"))
        .when(sandboxRunService).execute(anyLong(), any(), any());

    var result = mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"x\",\"language\":\"PYTHON\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    mvc.perform(asyncDispatch(result))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void oversizedCodeReturns400() throws Exception {
    String bigCode = "x".repeat(65537);
    mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + bigCode + "\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void missingLanguageReturns400() throws Exception {
    mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\"}"))
        .andExpect(status().isBadRequest());
  }
}
