package ai.devpath.sandbox.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SandboxSessionJpaTest {

  @Autowired SandboxSessionRepository repository;

  @Test
  void savesSandboxSessionMappingAllBuildAColumns() {
    SandboxSession session = new SandboxSession();
    session.setUserId(42L);
    session.setContentId(100L);
    session.setCodeBlockId(200L);
    session.setLanguage("PYTHON");
    session.setContainerId("container-1");
    session.setStatus("COMPLETED");
    session.setSubmittedCode("print(1)");
    session.setStdout("1\n");
    session.setStderr("");
    session.setExitCode(0);
    session.setCpuMsUsed(12L);
    session.setMemoryMbPeak(34);
    session.setStartedAt(Instant.now());
    session.setFinishedAt(Instant.now());

    SandboxSession saved = repository.saveAndFlush(session);

    assertNotNull(saved.getId());
    SandboxSession found = repository.findById(saved.getId()).orElseThrow();
    assertEquals(42L, found.getUserId());
    assertEquals("PYTHON", found.getLanguage());
    assertEquals("COMPLETED", found.getStatus());
    assertEquals(0, found.getExitCode());
    assertNotNull(found.getCreatedAt());
    assertNotNull(found.getUpdatedAt());
  }
}
