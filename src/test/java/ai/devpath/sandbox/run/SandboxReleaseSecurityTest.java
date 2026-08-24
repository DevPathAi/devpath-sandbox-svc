package ai.devpath.sandbox.run;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devpath.sandbox.config.InternalApiAuthenticationFilter;
import ai.devpath.sandbox.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SandboxReleaseController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "devpath.release.enabled=true",
    "devpath.auth.internal-token=release-internal-token"
})
class SandboxReleaseSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean SandboxReleaseFaultRegistry release;
  @MockitoBean SandboxReleaseStaleFixtureService stale;

  @Test
  void internalReleaseCommandRequiresTheExistingWorkloadCredential() throws Exception {
    String path = "/internal/release/sandbox/" + "a".repeat(64)
        + "/" + "R".repeat(43) + "/commands/next-run-timeout";
    mvc.perform(post(path).contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());

    mvc.perform(post(path)
            .header(InternalApiAuthenticationFilter.HEADER, "release-internal-token")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true));
  }
}
