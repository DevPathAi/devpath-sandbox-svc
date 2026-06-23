package ai.devpath.sandbox.run;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalSessionControllerTest {

  @Autowired MockMvc mvc;
  @Autowired SandboxSessionRepository sessions;

  @Test
  void returnsSessionViewWithoutAuth() throws Exception {
    SandboxSession s = new SandboxSession();
    s.setUserId(42L);
    s.setLanguage("PYTHON");
    s.setSubmittedCode("print(1)");
    s.setContentId(7L);
    s.setStatus("COMPLETED");
    s.setStdout("ok\n");
    s.setExitCode(0);
    s.setStartedAt(Instant.now());
    long id = sessions.save(s).getId();

    mvc.perform(get("/internal/sandbox/sessions/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(42))
        .andExpect(jsonPath("$.language").value("PYTHON"))
        .andExpect(jsonPath("$.contentId").value(7))
        .andExpect(jsonPath("$.submittedCode").value("print(1)"))
        .andExpect(jsonPath("$.stdout").value("ok\n"))
        .andExpect(jsonPath("$.exitCode").value(0))
        .andExpect(jsonPath("$.status").value("COMPLETED"));
  }

  @Test
  void missingSessionReturns404() throws Exception {
    mvc.perform(get("/internal/sandbox/sessions/999999999"))
        .andExpect(status().isNotFound());
  }
}
