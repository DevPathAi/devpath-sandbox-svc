package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
  @MockitoBean SandboxReleaseFaultRegistry releaseFaults;

  @Test
  void unauthenticatedRequestReturns401() throws Exception {
    mvc.perform(post("/sandbox/run")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validRequestReturnsSseStreamWithEarlySessionAndLogs() throws Exception {
    when(sandboxRunService.isRunnerAvailable()).thenReturn(true);
    doAnswer(inv -> {
      SandboxRunDelivery delivery = inv.getArgument(2);
      delivery.session(71L);
      delivery.log("Hello, World!");
      delivery.complete();
      return new AcceptedSandboxRun(71L);
    }).when(sandboxRunService).start(anyLong(), any(), any(), any());

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

    assertThat(sse).contains("event:session", "data:71", "event:log", "data:Hello, World!");
  }

  @Test
  void runnerUnavailableReturns503WithoutAdmission() throws Exception {
    when(sandboxRunService.isRunnerAvailable()).thenReturn(false);

    mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isServiceUnavailable());

    verify(sandboxRunService, never()).start(anyLong(), any(), any(), any());
    var order = inOrder(sandboxRunService);
    order.verify(sandboxRunService).assertCanAdmit(42L);
    order.verify(sandboxRunService).isRunnerAvailable();
  }

  @Test
  void cheapAdmissionRejectionSkipsCachedRunnerHealthLookup() throws Exception {
    org.mockito.Mockito.doThrow(new SandboxBusyException("active"))
        .when(sandboxRunService).assertCanAdmit(43L);

    mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("43")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isTooManyRequests());

    verify(sandboxRunService, never()).isRunnerAvailable();
    verify(sandboxRunService, never()).start(anyLong(), any(), any(), any());
  }

  @Test
  void exhaustedTerminalResultHeadroomFailsClosedWith503BeforeStarting() throws Exception {
    org.mockito.Mockito.doThrow(
        new SandboxUnavailableException("Sandbox terminal result capacity is full"))
        .when(sandboxRunService).assertCanAdmit(44L);

    mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("44")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isServiceUnavailable());

    verify(sandboxRunService, never()).isRunnerAvailable();
    verify(sandboxRunService, never()).start(anyLong(), any(), any(), any());
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

  @Test
  void blankCodeReturns400WithoutAdmission() throws Exception {
    mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"   \",\"language\":\"PYTHON\"}"))
        .andExpect(status().isBadRequest());

    verify(sandboxRunService, never()).start(anyLong(), any(), any(), any());
  }

  @Test
  void candidateAndRunHeadersSelectExactlyOneReleaseFaultPlan() throws Exception {
    when(sandboxRunService.isRunnerAvailable()).thenReturn(true);
    SandboxReleaseFaultPlan plan = org.mockito.Mockito.mock(SandboxReleaseFaultPlan.class);
    when(releaseFaults.consumeForRun("a".repeat(64), "R".repeat(43))).thenReturn(plan);
    doAnswer(inv -> {
      SandboxRunDelivery delivery = inv.getArgument(2);
      delivery.session(73L);
      delivery.complete();
      return new AcceptedSandboxRun(73L);
    }).when(sandboxRunService).start(anyLong(), any(), any(), any());

    var result = mvc.perform(post("/sandbox/run")
            .with(jwt().jwt(j -> j.subject("42")))
            .header("X-Candidate-Spec-Sha256", "a".repeat(64))
            .header("X-Release-Run-Key", "R".repeat(43))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

    verify(releaseFaults).consumeForRun("a".repeat(64), "R".repeat(43));
    verify(sandboxRunService).start(anyLong(), any(), any(), org.mockito.ArgumentMatchers.same(plan));
  }

  @Test
  void optionalIdsMustBePositiveAndJavascriptSafe() throws Exception {
    for (String ids : java.util.List.of(
        "\"contentId\":0",
        "\"contentId\":9007199254740992",
        "\"codeBlockId\":-1",
        "\"codeBlockId\":9007199254740992")) {
      mvc.perform(post("/sandbox/run")
              .with(jwt().jwt(j -> j.subject("42")))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"," + ids + "}"))
          .andExpect(status().isBadRequest());
    }

    verify(sandboxRunService, never()).start(anyLong(), any(), any(), any());
  }
}
