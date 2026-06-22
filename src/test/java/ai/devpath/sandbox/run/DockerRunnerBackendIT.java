package ai.devpath.sandbox.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("docker")
class DockerRunnerBackendIT {

  private final DockerRunnerBackend backend = new DockerRunnerBackend();

  @BeforeAll
  static void requireDocker() {
    assumeTrue(isDockerAvailable(), "Docker daemon is not available");
  }

  @Test
  void runsPythonHelloWorld() {
    List<String> logs = new ArrayList<>();

    RunResult result = backend.run(
        new RunSpec("print('PYTHON_OK')", "PYTHON", 1L),
        logs::add);

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("PYTHON_OK"));
    assertTrue(logs.stream().anyMatch(line -> line.contains("PYTHON_OK")));
  }

  @Test
  void runsNodeHelloWorld() {
    List<String> logs = new ArrayList<>();

    RunResult result = backend.run(
        new RunSpec("console.log('NODE_OK');", "NODE", 2L),
        logs::add);

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("NODE_OK"));
    assertTrue(logs.stream().anyMatch(line -> line.contains("NODE_OK")));
  }

  @Test
  void runsJavaHelloWorld() {
    List<String> logs = new ArrayList<>();
    String code = """
        public class Main {
          public static void main(String[] args) {
            System.out.println("JAVA_OK");
          }
        }
        """;

    RunResult result = backend.run(new RunSpec(code, "JAVA", 3L), logs::add);

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("JAVA_OK"));
    assertTrue(logs.stream().anyMatch(line -> line.contains("JAVA_OK")));
  }

  @Test
  void returnsMinusOneWhenExecutionTimesOut() {
    List<String> logs = new ArrayList<>();

    RunResult result = backend.run(
        new RunSpec("while True:\n    pass\n", "PYTHON", 4L),
        logs::add);

    assertEquals(-1, result.exitCode());
    assertTrue(result.stderr().contains("Execution timed out after 30s"));
    assertTrue(logs.stream().anyMatch(line -> line.contains("Execution timed out after 30s")));
  }

  @Test
  void returnsNonZeroForRuntimeError() {
    RunResult result = backend.run(
        new RunSpec("print(missing_name)", "PYTHON", 5L),
        line -> {});

    assertTrue(result.exitCode() > 0);
    assertTrue(result.stderr().contains("NameError"));
  }

  @Test
  void blocksNetworkAccess() {
    String code = """
        import socket

        sock = socket.socket()
        sock.settimeout(3)
        try:
            sock.connect(("1.1.1.1", 53))
            print("NETWORK_REACHABLE")
        except OSError:
            print("NETWORK_BLOCKED")
        finally:
            sock.close()
        """;

    RunResult result = backend.run(new RunSpec(code, "PYTHON", 6L), line -> {});

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("NETWORK_BLOCKED"));
    assertFalse(result.stdout().contains("NETWORK_REACHABLE"));
  }

  private static boolean isDockerAvailable() {
    try (DockerClient docker = DockerRunnerBackend.createDockerClient()) {
      docker.pingCmd().exec();
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }
}
