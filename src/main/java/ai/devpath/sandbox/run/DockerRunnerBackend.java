package ai.devpath.sandbox.run;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class DockerRunnerBackend implements RunnerBackend {

  private static final int TIMEOUT_SECONDS = 30;
  private static final long MEMORY_BYTES = 512L * 1024 * 1024;
  private static final long CPU_COUNT = 1L;
  private static final long PIDS_LIMIT = 128L;

  @Override
  public boolean isAvailable() {
    try {
      DockerClient docker = createDockerClient();
      docker.pingCmd().exec();
      docker.close();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public RunResult run(RunSpec spec, Consumer<String> logCallback) {
    Runtime runtime = Runtime.fromLanguage(spec.language());
    Path workspace = createWorkspace(spec, runtime);
    DockerClient docker = null;
    String containerId = null;
    ResultCallback.Adapter<Frame> logStream = null;
    StringBuilder stdout = new StringBuilder();
    StringBuilder stderr = new StringBuilder();

    try {
      docker = createDockerClient();
      docker.pingCmd().exec();

      HostConfig hostConfig = HostConfig.newHostConfig()
          .withNetworkMode("none")
          .withMemory(MEMORY_BYTES)
          .withCpuCount(CPU_COUNT)
          .withPidsLimit(PIDS_LIMIT)
          .withReadonlyRootfs(true)
          .withCapDrop(Capability.ALL)
          .withSecurityOpts(List.of("no-new-privileges:true"))
          .withTmpFs(Map.of("/tmp", "size=64m"))
          .withBinds(new Bind(
              workspace.toAbsolutePath().toString(),
              new Volume("/workspace"),
              AccessMode.ro));

      CreateContainerResponse container = docker.createContainerCmd(runtime.image())
          .withAttachStdout(true)
          .withAttachStderr(true)
          .withCmd(runtime.command())
          .withHostConfig(hostConfig)
          .withUser("nobody")
          .withWorkingDir("/workspace")
          .exec();
      containerId = container.getId();

      docker.startContainerCmd(containerId).exec();

      logStream = docker.logContainerCmd(containerId)
          .withStdOut(true)
          .withStdErr(true)
          .withFollowStream(true)
          .withTailAll()
          .exec(new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
              appendFrame(frame, stdout, stderr, logCallback);
            }
          });

      WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
      docker.waitContainerCmd(containerId).exec(waitCallback);
      boolean completed = waitCallback.awaitCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!completed) {
        killQuietly(docker, containerId);
        awaitLogs(logStream);
        String message = "Execution timed out after " + TIMEOUT_SECONDS + "s\n";
        stderr.append(message);
        logCallback.accept(message.stripTrailing());
        return new RunResult(-1, stdout.toString(), stderr.toString(), null, null);
      }

      Integer exitCode = waitCallback.awaitStatusCode();
      awaitLogs(logStream);
      return new RunResult(exitCode == null ? -1 : exitCode, stdout.toString(), stderr.toString(), null, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxUnavailableException("Interrupted while waiting for sandbox container", e);
    } catch (RuntimeException e) {
      throw new SandboxUnavailableException("Docker runner backend is unavailable", e);
    } finally {
      closeQuietly(logStream);
      if (docker != null && containerId != null) {
        removeContainerQuietly(docker, containerId);
      }
      closeQuietly(docker);
      deleteRecursively(workspace);
    }
  }

  static DockerClient createDockerClient() {
    DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost())
        .sslConfig(config.getSSLConfig())
        .connectionTimeout(Duration.ofSeconds(10))
        .responseTimeout(Duration.ofSeconds(TIMEOUT_SECONDS + 15L))
        .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }

  private Path createWorkspace(RunSpec spec, Runtime runtime) {
    try {
      Path workspace = Files.createTempDirectory("devpath-sandbox-" + spec.sandboxSessionId() + "-");
      Files.writeString(
          workspace.resolve(runtime.fileName()),
          spec.code() == null ? "" : spec.code(),
          StandardCharsets.UTF_8);
      return workspace;
    } catch (IOException e) {
      throw new SandboxUnavailableException("Could not prepare sandbox workspace", e);
    }
  }

  private static void appendFrame(
      Frame frame,
      StringBuilder stdout,
      StringBuilder stderr,
      Consumer<String> logCallback) {
    String chunk = new String(frame.getPayload(), StandardCharsets.UTF_8);
    if (frame.getStreamType() == StreamType.STDERR) {
      stderr.append(chunk);
    } else {
      stdout.append(chunk);
    }

    String line = chunk.stripTrailing();
    if (!line.isEmpty()) {
      logCallback.accept(line);
    }
  }

  private static void killQuietly(DockerClient docker, String containerId) {
    try {
      docker.killContainerCmd(containerId).exec();
    } catch (RuntimeException ignored) {
    }
  }

  private static void awaitLogs(ResultCallback.Adapter<Frame> logStream) throws InterruptedException {
    if (logStream != null) {
      logStream.awaitCompletion(2, TimeUnit.SECONDS);
    }
  }

  private static void removeContainerQuietly(DockerClient docker, String containerId) {
    try {
      docker.removeContainerCmd(containerId)
          .withForce(true)
          .withRemoveVolumes(true)
          .exec();
    } catch (RuntimeException ignored) {
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
    }
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
      });
    } catch (IOException ignored) {
    }
  }

  private record Runtime(String image, String fileName, String[] command) {

    static Runtime fromLanguage(String language) {
      String normalized = language == null ? "" : language.toUpperCase(Locale.ROOT);
      return switch (normalized) {
        case "JAVA" -> new Runtime(
            "eclipse-temurin:21-jdk",
            "Main.java",
            new String[] {"java", "/workspace/Main.java"});
        case "NODE" -> new Runtime(
            "node:20-alpine",
            "solution.js",
            new String[] {"node", "/workspace/solution.js"});
        case "PYTHON" -> new Runtime(
            "python:3.12-slim",
            "solution.py",
            new String[] {"python", "/workspace/solution.py"});
        default -> throw new IllegalArgumentException("Unsupported sandbox language: " + language);
      };
    }
  }
}
