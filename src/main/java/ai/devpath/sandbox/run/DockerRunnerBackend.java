package ai.devpath.sandbox.run;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ulimit;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DockerRunnerBackend implements RunnerBackend {

  private static final int TIMEOUT_SECONDS = 30;
  private static final int SETUP_DEADLINE_SECONDS = 20;
  private static final int SETUP_ORPHAN_DEADLINE_SECONDS = 4 * 45 + 15;
  // Container creation, durable attach, and start are bounded remote operations that happen
  // after labels are fixed. Their headroom cannot consume the user's execution timeout.
  private static final int EXECUTION_ORPHAN_DEADLINE_SECONDS = TIMEOUT_SECONDS + 2 * 45 + 15;
  private static final int VOLUME_ORPHAN_DEADLINE_SECONDS = 8 * 60;
  private static final long MEMORY_BYTES = 512L * 1024 * 1024;
  private static final long NANO_CPUS = 1_000_000_000L;
  private static final long PROCESS_LIMIT = 128L;
  private static final long LOADER_PROCESS_LIMIT = 16L;
  static final String MANAGED_LABEL = "ai.devpath.sandbox.managed";
  static final String SESSION_LABEL = "ai.devpath.sandbox.session-id";
  static final String DEADLINE_LABEL = "ai.devpath.sandbox.deadline";
  private static final ScheduledExecutorService DEADLINE_SCHEDULER =
      Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "sandbox-runner-deadline");
        thread.setDaemon(true);
        return thread;
      });

  private final SandboxRunnerProperties properties;
  private final SandboxDockerClientFactory clientFactory;
  private final long setupDeadlineMs;
  private final ConcurrentMap<Long, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();

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
    this(properties, clientFactory, Duration.ofSeconds(SETUP_DEADLINE_SECONDS));
  }

  DockerRunnerBackend(
      SandboxRunnerProperties properties,
      SandboxDockerClientFactory clientFactory,
      Duration setupDeadline) {
    if (setupDeadline == null || setupDeadline.isZero() || setupDeadline.isNegative()) {
      throw new IllegalArgumentException("Sandbox setup deadline must be positive");
    }
    this.properties = properties;
    this.clientFactory = clientFactory;
    this.setupDeadlineMs = Math.max(1L, setupDeadline.toMillis());
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
    DockerLogCapture capture = new DockerLogCapture(logCallback);
    ActiveExecution active = new ActiveExecution(Thread.currentThread());
    if (activeExecutions.putIfAbsent(spec.sandboxSessionId(), active) != null) {
      throw new SandboxUnavailableException("Sandbox session is already executing");
    }
    ScheduledFuture<?> setupDeadline = DEADLINE_SCHEDULER.schedule(
        active::cancelForSetupDeadline, setupDeadlineMs, TimeUnit.MILLISECONDS);
    boolean executionStartRequested = false;
    boolean containerStarted = false;

    try {
      active.throwIfCancelled();
      docker = openClient();
      active.attachDocker(docker);
      docker.pingCmd().exec();
      active.throwIfCancelled();

      Instant setupStarted = Instant.now();
      Map<String, String> volumeLabels = volumeLabels(spec.sandboxSessionId(), setupStarted);
      sourceVolume = sourceVolumeName(spec.sandboxSessionId());
      docker.createVolumeCmd()
          .withName(sourceVolume)
          .withLabels(volumeLabels)
          .exec();
      active.throwIfCancelled();

      Map<String, String> setupLabels =
          setupLabels(spec.sandboxSessionId(), Instant.now());
      CreateContainerResponse loader = docker.createContainerCmd(runtime.image())
          .withAttachStdout(false)
          .withAttachStderr(false)
          .withCmd("sh", "-c", "sleep 15")
          .withHostConfig(sourceLoaderHostConfig(sourceVolume, properties))
          .withUser("nobody")
          .withWorkingDir("/workspace")
          .withLabels(setupLabels)
          .exec();
      loaderContainerId = loader.getId();
      active.attachContainer(loaderContainerId);
      docker.startContainerCmd(loaderContainerId).exec();
      active.throwIfCancelled();
      docker.copyArchiveToContainerCmd(loaderContainerId)
          .withRemotePath("/workspace")
          .withTarInputStream(new ByteArrayInputStream(sourceArchive))
          .exec();
      removeContainerQuietly(docker, loaderContainerId);
      loaderContainerId = null;
      active.attachContainer(null);
      active.throwIfCancelled();

      HostConfig hostConfig = hardenedHostConfig(sourceVolume, properties);
      // Setup and source transfer do not consume the user's 30-second execution window.
      Map<String, String> executionLabels =
          executionLabelsFromStart(spec.sandboxSessionId(), Instant.now());

      CreateContainerResponse container = docker.createContainerCmd(runtime.image())
          .withAttachStdout(true)
          .withAttachStderr(true)
          .withCmd(runtime.command())
          .withHostConfig(hostConfig)
          .withUser("nobody")
          .withWorkingDir("/workspace")
          .withLabels(executionLabels)
          .exec();
      containerId = container.getId();
      active.attachContainer(containerId);

      // Persistence callback is intentionally before startContainerCmd. A failed
      // durable write removes the never-started container in finally.
      containerCreated.accept(containerId);
      active.throwIfCancelled();
      executionStartRequested = true;
      docker.startContainerCmd(containerId).exec();
      containerStarted = true;
      active.markExecutionStarted();
      setupDeadline.cancel(false);

      logStream = docker.logContainerCmd(containerId)
          .withStdOut(true)
          .withStdErr(true)
          .withFollowStream(true)
          .withTailAll()
          .exec(new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
              capture.onFrame(frame);
            }
          });
      active.attachLogStream(logStream);

      WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
      docker.waitContainerCmd(containerId).exec(waitCallback);
      boolean completed = waitCallback.awaitCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!completed) {
        killQuietly(docker, containerId);
        boolean logsCompleted = awaitLogs(logStream);
        if (!logsCompleted) {
          closeQuietly(logStream);
        }
        capture.finish(logsCompleted);
        String message = "Execution timed out after " + TIMEOUT_SECONDS + "s\n";
        capture.appendStderr(message);
        return capture.result(SandboxTerminalStatus.TIMED_OUT, -1, null, null);
      }

      Integer exitCode = waitCallback.awaitStatusCode();
      boolean logsCompleted = awaitLogs(logStream);
      if (!logsCompleted) {
        closeQuietly(logStream);
      }
      capture.finish(logsCompleted);
      int resolvedExitCode = exitCode == null ? -1 : exitCode;
      return capture.result(
          SandboxTerminalStatus.fromLegacyExitCode(resolvedExitCode),
          resolvedExitCode,
          null,
          null);
    } catch (InterruptedException e) {
      Thread.interrupted();
      closeQuietly(logStream);
      capture.finish(false);
      if (active.setupDeadlineExpired()) {
        throw new SandboxRunnerExecutionException(
            "Sandbox setup deadline exceeded",
            e,
            capture.result(SandboxTerminalStatus.FAILED, 1, null, null));
      }
      if (executionStartRequested || containerStarted || active.cancelled()) {
        throw executionFailure(capture, e, true);
      }
      throw new SandboxUnavailableException("Interrupted while waiting for sandbox container", e);
    } catch (RuntimeException e) {
      closeQuietly(logStream);
      capture.finish(false);
      if (active.setupDeadlineExpired()) {
        throw new SandboxRunnerExecutionException(
            "Sandbox setup deadline exceeded",
            e,
            capture.result(SandboxTerminalStatus.FAILED, 1, null, null));
      }
      if (executionStartRequested || containerStarted || active.cancelled()) {
        throw executionFailure(capture, e, active.cancelled());
      }
      throw new SandboxUnavailableException("Docker runner backend is unavailable", e);
    } finally {
      setupDeadline.cancel(false);
      activeExecutions.remove(spec.sandboxSessionId(), active);
      closeQuietly(logStream);
      cleanupBounded(docker, containerId, loaderContainerId, sourceVolume);
    }
  }

  @Override
  public void cancel(long sandboxSessionId) {
    ActiveExecution active = activeExecutions.get(sandboxSessionId);
    if (active != null) {
      active.cancelForDrain();
    }
  }

  static SandboxRunnerExecutionException executionFailure(
      DockerLogCapture capture,
      Throwable cause,
      boolean cancelled) {
    capture.finish(false);
    SandboxTerminalStatus status = cancelled
        ? SandboxTerminalStatus.KILLED
        : SandboxTerminalStatus.FAILED;
    return new SandboxRunnerExecutionException(
        cancelled
            ? "Sandbox execution was cancelled"
            : "Docker runner failed after execution was admitted",
        cause,
        capture.result(status, cancelled ? -1 : 1, null, null));
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
        // A Docker cgroup pids limit makes runsc fail during sandbox creation when
        // the Docker daemon itself runs in the dedicated DinD pod. RLIMIT_NPROC is
        // enforced by gVisor's application kernel and preserves the process/thread
        // ceiling without asking nested runsc to create another pids cgroup.
        .withUlimits(List.of(new Ulimit("nproc", PROCESS_LIMIT, PROCESS_LIMIT)))
        .withReadonlyRootfs(true)
        .withCapDrop(Capability.ALL)
        .withSecurityOpts(List.of("no-new-privileges:true"))
        .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=64m"))
        .withBinds(new Bind(sourceVolume, new Volume("/workspace"), AccessMode.ro))
        .withRuntime(properties.runtime().isBlank() ? null : properties.runtime());
  }

  static HostConfig sourceLoaderHostConfig(
      String sourceVolume, SandboxRunnerProperties properties) {
    return HostConfig.newHostConfig()
        .withNetworkMode("none")
        .withMemory(64L * 1024 * 1024)
        .withNanoCPUs(NANO_CPUS)
        .withUlimits(List.of(new Ulimit(
            "nproc", LOADER_PROCESS_LIMIT, LOADER_PROCESS_LIMIT)))
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

  static Map<String, String> setupLabels(long sessionId, Instant setupStarted) {
    return executionLabels(
        sessionId, setupStarted.plusSeconds(SETUP_ORPHAN_DEADLINE_SECONDS));
  }

  static Map<String, String> executionLabelsFromStart(long sessionId, Instant executionStarted) {
    return executionLabels(
        sessionId, executionStarted.plusSeconds(EXECUTION_ORPHAN_DEADLINE_SECONDS));
  }

  static Map<String, String> volumeLabels(long sessionId, Instant setupStarted) {
    return executionLabels(
        sessionId, setupStarted.plusSeconds(VOLUME_ORPHAN_DEADLINE_SECONDS));
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

  private static void killQuietly(DockerClient docker, String containerId) {
    try {
      docker.killContainerCmd(containerId).exec();
    } catch (RuntimeException ignored) {
    }
  }

  private static boolean awaitLogs(ResultCallback.Adapter<Frame> logStream)
      throws InterruptedException {
    return logStream == null || logStream.awaitCompletion(2, TimeUnit.SECONDS);
  }

  private static void cleanupBounded(
      DockerClient docker,
      String containerId,
      String loaderContainerId,
      String sourceVolume) {
    if (docker == null) {
      return;
    }
    Thread cleanup = Thread.ofVirtual().name("sandbox-runner-cleanup").start(() -> {
      if (containerId != null) {
        removeContainerQuietly(docker, containerId);
      }
      if (loaderContainerId != null) {
        removeContainerQuietly(docker, loaderContainerId);
      }
      if (sourceVolume != null) {
        removeVolumeQuietly(docker, sourceVolume);
      }
      closeQuietly(docker);
    });
    try {
      cleanup.join(5_000L);
    } catch (InterruptedException interrupted) {
      // Deployment cancellation must leave the caller able to persist its exact terminal result.
      Thread.interrupted();
    }
    if (cleanup.isAlive()) {
      closeQuietly(docker);
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

  private static final class ActiveExecution {
    private final Thread owner;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean executionStarted = new AtomicBoolean();
    private final AtomicBoolean setupDeadlineExpired = new AtomicBoolean();
    private volatile DockerClient docker;
    private volatile String containerId;
    private volatile ResultCallback.Adapter<Frame> logStream;

    private ActiveExecution(Thread owner) {
      this.owner = owner;
    }

    private void attachDocker(DockerClient value) {
      docker = value;
      if (cancelled.get()) {
        closeQuietly(value);
      }
    }

    private void attachContainer(String value) {
      containerId = value;
      if (cancelled.get() && value != null) {
        cancelRemoteAsync();
      }
    }

    private void attachLogStream(ResultCallback.Adapter<Frame> value) {
      logStream = value;
      if (cancelled.get()) {
        closeQuietly(value);
      }
    }

    private void markExecutionStarted() {
      executionStarted.set(true);
    }

    private void cancelForSetupDeadline() {
      if (executionStarted.get()) {
        return;
      }
      setupDeadlineExpired.set(true);
      cancel();
    }

    private void cancelForDrain() {
      cancel();
    }

    private void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        owner.interrupt();
        closeQuietly(logStream);
        cancelRemoteAsync();
      }
    }

    private void cancelRemoteAsync() {
      DockerClient activeDocker = docker;
      String activeContainerId = containerId;
      if (activeDocker == null) {
        return;
      }
      Thread.ofVirtual().name("sandbox-runner-cancel").start(() -> {
        if (activeContainerId != null) {
          killQuietly(activeDocker, activeContainerId);
        }
        closeQuietly(activeDocker);
      });
    }

    private void throwIfCancelled() {
      if (cancelled.get()) {
        throw new SandboxUnavailableException("Sandbox execution was cancelled");
      }
    }

    private boolean cancelled() {
      return cancelled.get();
    }

    private boolean setupDeadlineExpired() {
      return setupDeadlineExpired.get();
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
