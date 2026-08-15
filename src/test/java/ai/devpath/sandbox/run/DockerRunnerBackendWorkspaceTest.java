package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;

/** Guards remote-runner source transfer without a shared host filesystem. */
class DockerRunnerBackendWorkspaceTest {

  @Test
  void sourceArchivePreservesSubmittedUtf8WithoutHostFilesystem() throws Exception {
    byte[] bytes = DockerRunnerBackend.sourceArchive("solution.py", "print('원격_OK')");

    try (TarArchiveInputStream archive =
        new TarArchiveInputStream(new ByteArrayInputStream(bytes))) {
      var source = archive.getNextEntry();
      assertThat(source.getName()).isEqualTo("solution.py");
      assertThat(source.getMode() & 0777).isEqualTo(0444);
      assertThat(new String(archive.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("print('원격_OK')");
      assertThat(archive.getNextEntry()).isNull();
    }
  }
}
