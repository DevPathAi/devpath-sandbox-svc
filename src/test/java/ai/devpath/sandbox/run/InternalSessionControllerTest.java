package ai.devpath.sandbox.run;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.jpa.repository.Query;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalSessionControllerTest {

  private static final String INTERNAL_TOKEN = "test-internal-token";

  @Autowired MockMvc mvc;
  @Autowired SandboxSessionRepository sessions;

  // @SpringBootTest는 롤백하지 않으므로 userId 기준 조회 테스트가 누적되지 않게 매 테스트 전 정리.
  @BeforeEach
  void cleanup() {
    sessions.deleteAll();
  }

  @Test
  void returnsSessionViewWithWorkloadAuth() throws Exception {
    SandboxSession s = new SandboxSession();
    s.setUserId(42L);
    s.setLanguage("PYTHON");
    s.setSubmittedCode("print(1)");
    s.setContentId(7L);
    s.setCodeBlockId(8L);
    s.setStatus("COMPLETED");
    s.setStdout("ok\n");
    s.setExitCode(0);
    s.setOutputTruncated(true);
    s.setStartedAt(Instant.now());
    long id = sessions.save(s).getId();

    mvc.perform(internal(get("/internal/sandbox/sessions/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(42))
        .andExpect(jsonPath("$.language").value("PYTHON"))
        .andExpect(jsonPath("$.contentId").value(7))
        .andExpect(jsonPath("$.codeBlockId").value(8))
        .andExpect(jsonPath("$.submittedCode").value("print(1)"))
        .andExpect(jsonPath("$.stdout").value("ok\n"))
        .andExpect(jsonPath("$.exitCode").value(0))
        .andExpect(jsonPath("$.outputTruncated").value(true))
        .andExpect(jsonPath("$.status").value("COMPLETED"));
  }

  @Test
  void missingSessionReturns404() throws Exception {
    mvc.perform(internal(get("/internal/sandbox/sessions/999999999")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
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

    mvc.perform(internal(get("/internal/sandbox/sessions/recent")
            .param("userId", String.valueOf(userId)))
        )
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

    mvc.perform(internal(get("/internal/sandbox/sessions/recent")
            .param("userId", String.valueOf(userId))
            .param("limit", "2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].submittedCode").value("s3"))
        .andExpect(jsonPath("$[1].submittedCode").value("s2"));
  }

  @Test
  void recentReturnsEmptyArrayWhenNoSessions() throws Exception {
    mvc.perform(internal(get("/internal/sandbox/sessions/recent")
            .param("userId", "70030001")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void recentClampsLimitToUpperBound() throws Exception {
    long userId = 7004L;
    for (int i = 0; i < 22; i++) {
      saveSession(userId, "c" + i, Instant.parse("2026-06-24T10:00:00Z").plusSeconds(i));
    }

    mvc.perform(internal(get("/internal/sandbox/sessions/recent")
            .param("userId", String.valueOf(userId))
            .param("limit", "999")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(20));
  }

  @Test
  void recentMetadataReturnsOnlyLanguageAndStatusWithoutHydratingSecrets() throws Exception {
    long userId = 7010L;
    SandboxSession secret = new SandboxSession();
    secret.setUserId(userId);
    secret.setLanguage("JAVA");
    secret.setSubmittedCode("SECRET_CODE");
    secret.setStdout("SECRET_STDOUT");
    secret.setStderr("SECRET_STDERR");
    secret.setContentId(91L);
    secret.setCodeBlockId(92L);
    secret.setExitCode(0);
    secret.setOutputTruncated(true);
    secret.setStatus("COMPLETED");
    secret.setStartedAt(Instant.parse("2026-06-24T12:00:00Z"));
    sessions.save(secret);

    String body = mvc.perform(internal(get("/internal/sandbox/sessions/recent/metadata")
            .param("userId", String.valueOf(userId))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].length()").value(2))
        .andExpect(jsonPath("$[0].language").value("JAVA"))
        .andExpect(jsonPath("$[0].status").value("COMPLETED"))
        .andExpect(jsonPath("$[0].submittedCode").doesNotExist())
        .andExpect(jsonPath("$[0].stdout").doesNotExist())
        .andExpect(jsonPath("$[0].stderr").doesNotExist())
        .andExpect(jsonPath("$[0].id").doesNotExist())
        .andExpect(jsonPath("$[0].userId").doesNotExist())
        .andExpect(jsonPath("$[0].contentId").doesNotExist())
        .andExpect(jsonPath("$[0].codeBlockId").doesNotExist())
        .andExpect(jsonPath("$[0].exitCode").doesNotExist())
        .andExpect(jsonPath("$[0].outputTruncated").doesNotExist())
        .andReturn().getResponse().getContentAsString();

    assertFalse(body.contains("SECRET_CODE"));
    assertFalse(body.contains("SECRET_STDOUT"));
    assertFalse(body.contains("SECRET_STDERR"));
  }

  @Test
  void recentMetadataClampsLimitAndRequiresWorkloadToken() throws Exception {
    long userId = 7011L;
    for (int i = 0; i < 22; i++) {
      saveSession(userId, "SECRET_" + i,
          Instant.parse("2026-06-24T10:00:00Z").plusSeconds(i));
    }

    mvc.perform(internal(get("/internal/sandbox/sessions/recent/metadata")
            .param("userId", String.valueOf(userId))
            .param("limit", "999")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(20))
        .andExpect(jsonPath("$[0].length()").value(2));

    mvc.perform(get("/internal/sandbox/sessions/recent/metadata")
            .param("userId", String.valueOf(userId)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void recentMetadataRepositoryUsesAnExplicitTwoColumnProjection() {
    var projectionMethod = Arrays.stream(SandboxSessionRepository.class.getMethods())
        .filter(method -> method.getName().equals("findRecentMetadataByUserId"))
        .findFirst();

    assertTrue(projectionMethod.isPresent(), "dedicated metadata projection query is required");
    Query query = projectionMethod.orElseThrow().getAnnotation(Query.class);
    assertTrue(query != null, "metadata projection must declare an explicit query");
    String statement = query.value();
    assertTrue(statement.contains("SandboxSessionMetadata(session.language, session.status)"));
    assertFalse(statement.contains("select session "));
    assertFalse(statement.contains("submittedCode"));
    assertFalse(statement.contains("stdout"));
    assertFalse(statement.contains("stderr"));
  }

  @Test
  void internalApiRejectsMissingAndWrongWorkloadTokens() throws Exception {
    mvc.perform(get("/internal/sandbox/sessions/recent").param("userId", "1"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/internal/sandbox/sessions/recent")
            .header("X-DevPath-Internal-Token", "wrong")
            .param("userId", "1"))
        .andExpect(status().isUnauthorized());
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder internal(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
    return request.header("X-DevPath-Internal-Token", INTERNAL_TOKEN);
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
