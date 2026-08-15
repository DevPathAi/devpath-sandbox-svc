package ai.devpath.sandbox.run;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SandboxSessionControllerTest {

  @Autowired MockMvc mvc;
  @Autowired SandboxSessionRepository sessions;

  @BeforeEach
  void cleanup() {
    sessions.deleteAll();
  }

  @Test
  void ownerRecoversTerminalStatusAndPersistedTruncation() throws Exception {
    long id = saveSession(42L, "TIMED_OUT", true);

    mvc.perform(get("/sandbox/sessions/" + id)
            .with(jwt().jwt(j -> j.subject("42"))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.sessionId").value((int) id))
        .andExpect(jsonPath("$.status").value("TIMED_OUT"))
        .andExpect(jsonPath("$.truncated").value(true))
        .andExpect(jsonPath("$.exitCode").value(-1));
  }

  @Test
  void differentOwnerGets404WithoutSessionDisclosure() throws Exception {
    long id = saveSession(42L, "COMPLETED", false);

    mvc.perform(get("/sandbox/sessions/" + id)
            .with(jwt().jwt(j -> j.subject("43"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void recoveryRequiresAuthentication() throws Exception {
    long id = saveSession(42L, "COMPLETED", false);

    mvc.perform(get("/sandbox/sessions/" + id))
        .andExpect(status().isUnauthorized());
  }

  private long saveSession(long userId, String status, boolean truncated) {
    SandboxSession session = new SandboxSession();
    session.setUserId(userId);
    session.setLanguage("PYTHON");
    session.setSubmittedCode("print(1)");
    session.setStatus(status);
    session.setStdout("partial");
    session.setStderr("timeout");
    session.setExitCode(status.equals("TIMED_OUT") ? -1 : 0);
    session.setOutputTruncated(truncated);
    session.setStartedAt(Instant.now());
    session.setFinishedAt(Instant.now());
    return sessions.saveAndFlush(session).getId();
  }
}
