package ai.devpath.sandbox.run;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the root cause of the docker IT failures: {@link Files#createTempDirectory} makes the
 * workspace owner-only (0700), which the unprivileged {@code nobody} container user cannot read
 * over the read-only bind mount on a native Linux host. This is a plain (non-{@code docker}) unit
 * test so it runs in the standard build; it is skipped on non-POSIX hosts where the contract does
 * not apply.
 */
class DockerRunnerBackendWorkspaceTest {

  @Test
  void workspaceIsReadableByUnprivilegedContainerUser() throws Exception {
    assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "POSIX permissions are not supported on this host");

    Path workspace = DockerRunnerBackend.prepareWorkspace(99L, "solution.py", "print('OK')");
    try {
      Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(workspace);
      Set<PosixFilePermission> filePerms =
          Files.getPosixFilePermissions(workspace.resolve("solution.py"));

      assertTrue(
          dirPerms.contains(PosixFilePermission.OTHERS_EXECUTE),
          "workspace dir must be traversable by the container user (others-execute), was "
              + dirPerms);
      assertTrue(
          filePerms.contains(PosixFilePermission.OTHERS_READ),
          "source file must be readable by the container user (others-read), was " + filePerms);
    } finally {
      deleteRecursively(workspace);
    }
  }

  private static void deleteRecursively(Path root) throws Exception {
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (Exception ignored) {
          // best-effort cleanup
        }
      });
    }
  }
}
