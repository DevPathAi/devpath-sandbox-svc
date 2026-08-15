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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DockerRunnerBackend implements RunnerBackend {

  private static final int TIMEOUT_SECONDS = 30;
  private static final int ORPHAN_DEADLINE_SECONDS = TIMEOUT_SECONDS + 15;
  private static final long MEMORY_BYTES = 512L * 1024 * 1024;
  private static final long NANO_CPUS = 1_000_000_000L;
  private static final long PIDS_LIMIT = 128L;
  static final String MANAGED_LABEL = "ai.devpath.sandbox.managed";
  static final String SESSION_LABEL = "ai.devpath.sandbox.session-id";
  static final String DEADLINE_LABEL = "ai.devpath.sandbox.deadline";

  private final SandboxRunnerProperties properties;
  private final SandboxDockerClientFactory clientFactory;

  public DockerRunnerBackend() {
    this(SandboxRunnerProperties.development());
  }

  @Autowired
  public DockerRunnerBackend(SandboxRunnerProperties properties) {
    this(properties, () -> createDockerClient(properties));
  }

  DockerRunnerBackend(
      SandboxRunnerProperties properties,
      SandboxDockerClientFactory clientFactory) {
    this.properties = properties;
    this.clientFactory = clientFactory;
  }

  @Override
  public boolean isAvailable() {
    try (DockerClient docker = openClient()) {
      docker.pingCmd().exec();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public RunResult run(RunSpec spec, Consumer<String> logCallback) {
    return run(spec, logCallback, ignored -> {});
  }

  @Override
  public RunResult run(
      RunSpec spec,
      Consumer<String> logCallback,
      Consumer<String> containerCreated) {
    properties.assertSecure();
    Runtime runtime = Runtime.fromLanguage(spec.language());
    byte[] sourceArchive = createSourceArchive(runtime.fileName(), spec.code());
    DockerClient docker = null;
    String containerId = null;
    String loaderContainerId = null;
    String sourceVolume = null;
    ResultCallback.Adapter<Frame> logStream = null;
    SandboxOutputCapture output = new SandboxOutputCapture();

    try {
      docker = openClient();
      docker.pingCmd().exec();

      // Keep the orphan deadline beyond the synchronous execution timeout so
      // the independent reaper cannot relabel a live timeout as KILLED.
      Instant deadline = Instant.now().plusSeconds(ORPHAN_DEADLINE_SECONDS);
      Map<String, String> labels = executionLabels(spec.sandboxSessionId(), deadline);
      sourceVolume = sourceVolumeName(spec.sandboxSessionId());
      docker.createVolumeCmd()
          .withName(sourceVolume)
          .withLabels(labels)
          .exec();

      CreateContainerResponse loader = docker.createContainerCmd(runtime.image())
          .withAttachStdout(false)
          .withAttachStderr(false)
          .withCmd("sh", "-c", "sleep 15")
          .withHostConfig(sourceLoaderHostConfig(sourceVolume, properties))
          .withUser("nobody")
          .withWorkingDir("/workspace")
          .withLabels(labels)
          .exec();
      loaderContainerId = loader.getId();
      docker.startContainerCmd(loaderContainerId).exec();
      docker.copyArchiveToContainerCmd(loaderContainerId)
          .withRemotePath("/workspace")
          .withTarInputStream(new ByteArrayInputStream(sourceArchive))
          .exec();
      removeContainerQuietly(docker, loaderContainerId);
      loaderContainerId = null;

      HostConfig hostConfig = hardenedHostConfig(sourceVolume, properties);

      CreateContainerResponse container = docker.createContainerCmd(runtime.image())
          .withAttachStdout(true)
          .withAttachStderr(true)
          .withCmd(runtime.command())
          .withHostConfig(hostConfig)
          .withUser("nobody")
          .withWorkingDir("/workspace")
          .withLabels(labels)
          .exec();
      containerId = container.getId();

      // Persistence callback is intentionally before startContainerCmd. A failed
      // durable write removes the never-started container in finally.
      containerCreated.accept(containerId);
      docker.startContainerCmd(containerId).exec();

      logStream = docker.logContainerCmd(containerId)
          .withStdOut(true)
          .withStdErr(true)
          .withFollowStream(true)
          .withTailAll()
          .exec(new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
              appendFrame(frame, output, logCallback);
            }
          });

      WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
      docker.waitContainerCmd(containerId).exec(waitCallback);
      boolean completed = waitCallback.awaitCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!completed) {
        killQuietly(docker, containerId);
        awaitLogs(logStream);
        String message = "Execution timed out after " + TIMEOUT_SECONDS + "s\n";
        String accepted = output.appendStderr(message);
        if (!accepted.isEmpty()) {
          logCallback.accept(accepted.stripTrailing());
        }
        return output.result(SandboxTerminalStatus.TIMED_OUT, -1, null, null);
      }

      Integer exitCode = waitCallback.awaitStatusCode();
      awaitLogs(logStream);
      int resolvedExitCode = exitCode == null ? -1 : exitCode;
      return output.result(
          SandboxTerminalStatus.fromLegacyExitCode(resolvedExitCode),
          resolvedExitCode,
          null,
          null);
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
      if (docker != null && loaderContainerId != null) {
        removeContainerQuietly(docker, loaderContainerId);
      }
      if (docker != null && sourceVolume != null) {
        removeVolumeQuietly(docker, sourceVolume);
      }
      closeQuietly(docker);
    }
  }

  static DockerClient createDockerClient() {
    return createDockerClient(SandboxRunnerProperties.development());
  }

  static DockerClient createDockerClient(SandboxRunnerProperties properties) {
    properties.assertSecure();
    var builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
    if (!properties.endpoint().isBlank()) {
      builder.withDockerHost(properties.endpoint());
    }
    builder.withDockerTlsVerify(properties.tlsVerify());
    if (!properties.certPath().isBlank()) {
      builder.withDockerCertPath(properties.certPath());
    }
    DockerClientConfig config = builder.build();
    DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost())
        .sslConfig(config.getSSLConfig())
        .connectionTimeout(Duration.ofSeconds(10))
        .responseTimeout(Duration.ofSeconds(TIMEOUT_SECONDS + 15L))
        .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }

  private DockerClient openClient() {
    properties.assertSecure();
    return clientFactory.create();
  }

  static HostConfig hardenedHostConfig(
      String sourceVolume, SandboxRunnerProperties properties) {
    properties.assertSecure();
    return HostConfig.newHostConfig()
        .withNetworkMode("none")
        .withMemory(MEMORY_BYTES)
        .withNanoCPUs(NANO_CPUS)
        .withPidsLimit(PIDS_LIMIT)
        .withReadonlyRootfs(true)
        .withCapDrop(Capability.ALL)
        .withSecurityOpts(List.of("no-new-privileges:true"))
        .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=64m"))
        .withBinds(new Bind(sourceVolume, new Volume("/workspace"), AccessMode.ro))
        .withRuntime(properties.runtime().isBlank() ? null : properties.runtime());
  }

  private static HostConfig sourceLoaderHostConfig(
      String sourceVolume, SandboxRunnerProperties properties) {
    return HostConfig.newHostConfig()
        .withNetworkMode("none")
        .withMemory(64L * 1024 * 1024)
        .withNanoCPUs(NANO_CPUS)
        .withPidsLimit(16L)
        .withCapDrop(Capability.ALL)
        .withSecurityOpts(List.of("no-new-privileges:true"))
        .withBinds(new Bind(sourceVolume, new Volume("/workspace"), AccessMode.rw))
        .withRuntime(properties.runtime().isBlank() ? null : properties.runtime());
  }

  static Map<String, String> executionLabels(long sessionId, Instant deadline) {
    return Map.of(
        MANAGED_LABEL, "true",
        SESSION_LABEL, String.valueOf(sessionId),
        DEADLINE_LABEL, deadline.toString());
  }

  static boolean isExpiredContainer(Map<String, String> labels, Instant now) {
    if (labels == null || !"true".equals(labels.get(MANAGED_LABEL))) {
      return false;
    }
    try {
      return !Instant.parse(labels.get(DEADLINE_LABEL)).isAfter(now);
    } catch (RuntimeException invalidDeadline) {
      return false;
    }
  }

  @Override
  public int reapExpiredContainers(Instant now) {
    properties.assertSecure();
    int reaped = 0;
    try (DockerClient docker = openClient()) {
      var containers = docker.listContainersCmd()
          .withShowAll(true)
          .withLabelFilter(Map.of(MANAGED_LABEL, "true"))
          .exec();
      for (var container : containers) {
        if (!isExpiredContainer(container.getLabels(), now)) {
          continue;
        }
        killQuietly(docker, container.getId());
        if (removeContainer(docker, container.getId())) {
          reaped++;
        }
      }
      var volumeResponse = docker.listVolumesCmd()
          .withFilter("label", List.of(MANAGED_LABEL + "=true"))
          .exec();
      if (volumeResponse != null && volumeResponse.getVolumes() != null) {
        for (var volume : volumeResponse.getVolumes()) {
          if (isExpiredContainer(volume.getLabels(), now)
              && removeVolume(docker, volume.getName())) {
            reaped++;
          }
        }
      }
      return reaped;
    } catch (Exception failure) {
      throw new SandboxUnavailableException("Could not reap Sandbox runner orphans", failure);
    }
  }

  private static byte[] createSourceArchive(String fileName, String code) {
    try {
      return sourceArchive(fileName, code);
    } catch (IOException e) {
      throw new SandboxUnavailableException("Could not prepare Sandbox source archive", e);
    }
  }

  static byte[] sourceArchive(String fileName, String code) throws IOException {
    byte[] source = (code == null ? "" : code).getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes)) {
      TarArchiveEntry entry = new TarArchiveEntry(fileName);
      entry.setMode(0444);
      entry.setSize(source.length);
      archive.putArchiveEntry(entry);
      archive.write(source);
      archive.closeArchiveEntry();
      archive.finish();
    }
    return bytes.toByteArray();
  }

  static String sourceVolumeName(long sessionId) {
    return "devpath-sandbox-" + sessionId + "-" + java.util.UUID.randomUUID();
  }

  private static void appendFrame(
      Frame frame,
      SandboxOutputCapture output,
      Consumer<String> logCallback) {
    String chunk = new String(frame.getPayload(), StandardCharsets.UTF_8);
    String accepted;
    if (frame.getStreamType() == StreamType.STDERR) {
      accepted = output.appendStderr(chunk);
    } else {
      accepted = output.appendStdout(chunk);
    }

    String line = accepted.stripTrailing();
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
    removeContainer(docker, containerId);
  }

  private static boolean removeContainer(DockerClient docker, String containerId) {
    try {
      docker.removeContainerCmd(containerId)
          .withForce(true)
          .withRemoveVolumes(true)
          .exec();
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static void removeVolumeQuietly(DockerClient docker, String volumeName) {
    removeVolume(docker, volumeName);
  }

  private static boolean removeVolume(DockerClient docker, String volumeName) {
    try {
      docker.removeVolumeCmd(volumeName).exec();
      return true;
    } catch (RuntimeException ignored) {
      return false;
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
