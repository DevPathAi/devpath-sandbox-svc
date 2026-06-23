package ai.devpath.sandbox.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

  @Autowired MockMvc mvc;

  @Test
  void healthIsPublic() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void sandboxRunRequiresAuth() throws Exception {
    mvc.perform(post("/sandbox/run")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"print(1)\",\"language\":\"PYTHON\"}"))
        .andExpect(status().isUnauthorized());
  }
}
