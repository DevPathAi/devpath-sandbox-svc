package ai.devpath.sandbox.run;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class InternalSessionControllerTest {

  @Autowired MockMvc mvc;
  @Autowired SandboxSessionRepository sessions;

  // @SpringBootTest는 롤백하지 않으므로 userId 기준 조회 테스트가 누적되지 않게 매 테스트 전 정리.
  @BeforeEach
  void cleanup() {
    sessions.deleteAll();
  }

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

  @Test
  void recentReturnsSessionsForUserOrderedByStartedAtDesc() throws Exception {
    long userId = 7001L;
    // 삽입 순서와 started_at 순서를 어긋나게 해서 정렬을 실증한다.
    saveSession(userId, "older", Instant.parse("2026-06-24T10:00:00Z"));
    saveSession(userId, "newest", Instant.parse("2026-06-24T12:00:00Z"));
    saveSession(userId, "middle", Instant.parse("2026-06-24T11:00:00Z"));
    // 다른 사용자 세션은 결과에 섞이면 안 된다.
    saveSession(9999L, "other-user", Instant.parse("2026-06-24T13:00:00Z"));

    mvc.perform(get("/internal/sandbox/sessions/recent")
            .param("userId", String.valueOf(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].userId").value((int) userId))
        .andExpect(jsonPath("$[0].submittedCode").value("newest"))
        .andExpect(jsonPath("$[1].submittedCode").value("middle"))
        .andExpect(jsonPath("$[2].submittedCode").value("older"));
  }

  @Test
  void recentRespectsLimitParameter() throws Exception {
    long userId = 7002L;
    saveSession(userId, "s1", Instant.parse("2026-06-24T10:00:00Z"));
    saveSession(userId, "s2", Instant.parse("2026-06-24T11:00:00Z"));
    saveSession(userId, "s3", Instant.parse("2026-06-24T12:00:00Z"));

    mvc.perform(get("/internal/sandbox/sessions/recent")
            .param("userId", String.valueOf(userId))
            .param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].submittedCode").value("s3"))
        .andExpect(jsonPath("$[1].submittedCode").value("s2"));
  }

  @Test
  void recentReturnsEmptyArrayWhenNoSessions() throws Exception {
    mvc.perform(get("/internal/sandbox/sessions/recent")
            .param("userId", "70030001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void recentClampsLimitToUpperBound() throws Exception {
    long userId = 7004L;
    for (int i = 0; i < 22; i++) {
      saveSession(userId, "c" + i, Instant.parse("2026-06-24T10:00:00Z").plusSeconds(i));
    }

    mvc.perform(get("/internal/sandbox/sessions/recent")
            .param("userId", String.valueOf(userId))
            .param("limit", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(20));
  }

  private void saveSession(long userId, String code, Instant startedAt) {
    SandboxSession s = new SandboxSession();
    s.setUserId(userId);
    s.setLanguage("PYTHON");
    s.setSubmittedCode(code);
    s.setStatus("COMPLETED");
    s.setExitCode(0);
    s.setStartedAt(startedAt);
    sessions.save(s);
  }
}
