package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListVolumesCmd;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DockerRunnerHardeningTest {

  @Test
  void requiredIsolationRejectsLocalSocketPlainRuntimeAndMissingTls() {
    assertThrows(IllegalStateException.class, () ->
        new SandboxRunnerProperties("unix:///var/run/docker.sock", "runsc", true, true, "")
            .assertSecure());
    assertThrows(IllegalStateException.class, () ->
        new SandboxRunnerProperties("tcp://runner:2376", "runc", true, true, "/certs")
            .assertSecure());
    assertThrows(IllegalStateException.class, () ->
        new SandboxRunnerProperties("tcp://runner:2376", "runsc", true, false, "/certs")
            .assertSecure());
    assertThrows(IllegalStateException.class, () ->
        new SandboxRunnerProperties("tcp://runner:2376", "runsc", true, true, "")
            .assertSecure());
  }

  @Test
  void hardenedHostConfigUsesRunscNanoCpuAndNoNetwork() {
    SandboxRunnerProperties properties =
        new SandboxRunnerProperties("tcp://runner:2376", "runsc", true, true, "/certs");

    var config = DockerRunnerBackend.hardenedHostConfig(
        "devpath-sandbox-91-source", properties);

    assertThat(config.getRuntime()).isEqualTo("runsc");
    assertThat(config.getNanoCPUs()).isEqualTo(1_000_000_000L);
    assertThat(config.getNetworkMode()).isEqualTo("none");
    assertThat(config.getMemory()).isEqualTo(512L * 1024 * 1024);
    assertThat(config.getPidsLimit()).isEqualTo(128L);
    assertThat(config.getReadonlyRootfs()).isTrue();
    assertThat(config.getSecurityOpts()).contains("no-new-privileges:true");
    assertThat(config.getBinds()).hasSize(1);
    assertThat(config.getBinds()[0].getPath()).isEqualTo("devpath-sandbox-91-source");
    assertThat(config.getBinds()[0].getAccessMode()).isEqualTo(com.github.dockerjava.api.model.AccessMode.ro);
    assertThat(config.getTmpFs()).containsKey("/tmp");
  }

  @Test
  void managedLabelsCarrySessionAndExecutionDeadline() {
    Instant deadline = Instant.parse("2026-08-16T00:00:30Z");

    var labels = DockerRunnerBackend.executionLabels(91L, deadline);

    assertThat(labels).containsEntry("ai.devpath.sandbox.managed", "true")
        .containsEntry("ai.devpath.sandbox.session-id", "91")
        .containsEntry("ai.devpath.sandbox.deadline", deadline.toString());
    assertThat(DockerRunnerBackend.isExpiredContainer(
        labels, Instant.parse("2026-08-16T00:00:31Z"))).isTrue();
  }

  @Test
  void executionGetsAFullTimeoutWindowAfterDelayedSetupAndVolumeDeadlineStaysSafe() {
    Instant setupStarted = Instant.parse("2026-08-16T00:00:00Z");
    Instant executionStarted = setupStarted.plusSeconds(40);

    Map<String, String> setup = DockerRunnerBackend.setupLabels(91L, setupStarted);
    Map<String, String> execution =
        DockerRunnerBackend.executionLabelsFromStart(91L, executionStarted);
    Map<String, String> volume = DockerRunnerBackend.volumeLabels(91L, setupStarted);

    Instant setupDeadline = Instant.parse(setup.get(DockerRunnerBackend.DEADLINE_LABEL));
    Instant executionDeadline = Instant.parse(
        execution.get(DockerRunnerBackend.DEADLINE_LABEL));
    Instant volumeDeadline = Instant.parse(volume.get(DockerRunnerBackend.DEADLINE_LABEL));

    assertThat(setupDeadline).isAfterOrEqualTo(setupStarted.plusSeconds(4 * 45L));
    assertThat(executionDeadline).isAfterOrEqualTo(executionStarted.plusSeconds(45));
    assertThat(volumeDeadline).isAfter(setupDeadline);
    assertThat(volumeDeadline).isAfter(executionDeadline);
  }

  @Test
  void utf8DecoderPreservesCodePointsSplitAcrossDockerFramesPerStream() {
    Utf8StreamDecoder stdout = new Utf8StreamDecoder();
    Utf8StreamDecoder stderr = new Utf8StreamDecoder();
    byte[] korean = "가나다".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] emoji = "실패🔥".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    String first = stdout.decode(java.util.Arrays.copyOfRange(korean, 0, 2));
    String errorFirst = stderr.decode(java.util.Arrays.copyOfRange(emoji, 0, 4));
    String second = stdout.decode(java.util.Arrays.copyOfRange(korean, 2, 5));
    String errorSecond = stderr.decode(java.util.Arrays.copyOfRange(emoji, 4, emoji.length));
    String third = stdout.decode(java.util.Arrays.copyOfRange(korean, 5, korean.length));

    assertThat(first + second + third + stdout.finish()).isEqualTo("가나다");
    assertThat(errorFirst + errorSecond + stderr.finish()).isEqualTo("실패🔥");
  }

  @Test
  void incompleteLogDrainClosesDecodersAndLateFramesOnlyMarkTheResultTruncated() {
    java.util.List<String> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
    DockerLogCapture capture = new DockerLogCapture(delivered::add);
    byte[] output = "완료🙂".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    capture.onFrame(new Frame(
        StreamType.STDOUT, java.util.Arrays.copyOfRange(output, 0, output.length - 1)));
    capture.finish(false);
    capture.onFrame(new Frame(
        StreamType.STDOUT, java.util.Arrays.copyOfRange(output, output.length - 1, output.length)));
    RunResult result = capture.result(SandboxTerminalStatus.FAILED, 1, null, null);

    assertThat(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(result.stdout()))
        .isTrue();
    assertThat(result.stdout()).startsWith("완료");
    assertThat(result.outputTruncated()).isTrue();
    assertThat(delivered).isNotEmpty();
  }

  @Test
  void transportFailureAfterContainerStartCarriesCapturedStdoutAndStderr() {
    DockerLogCapture capture = new DockerLogCapture(ignored -> {});
    capture.onFrame(new Frame(
        StreamType.STDOUT,
        "부분🙂".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    capture.onFrame(new Frame(
        StreamType.STDERR,
        "경고".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    SandboxRunnerExecutionException failure = DockerRunnerBackend.executionFailure(
        capture, new IllegalStateException("transport closed"), false);

    assertThat(failure.result().terminalStatus()).isEqualTo(SandboxTerminalStatus.FAILED);
    assertThat(failure.result().stdout()).isEqualTo("부분🙂");
    assertThat(failure.result().stderr()).isEqualTo("경고");
    assertThat(failure.result().outputTruncated()).isTrue();
  }

  @Test
  void failedPingStillClosesDockerClientAndTransport() throws Exception {
    SandboxRunnerProperties properties =
        new SandboxRunnerProperties("tcp://runner:2376", "runsc", true, true, "/certs");
    DockerClient client = mock(DockerClient.class);
    PingCmd ping = mock(PingCmd.class);
    when(client.pingCmd()).thenReturn(ping);
    doThrow(new IllegalStateException("down")).when(ping).exec();
    SandboxDockerClientFactory factory = mock(SandboxDockerClientFactory.class);
    when(factory.create()).thenReturn(client);
    DockerRunnerBackend backend = new DockerRunnerBackend(properties, factory);

    assertThat(backend.isAvailable()).isFalse();

    verify(client).close();
  }

  @Test
  void shortenedSetupDeadlineInterruptsABlockingRemoteOperationAndReturnsExactFailure()
      throws Exception {
    DockerClient client = mock(DockerClient.class);
    PingCmd ping = mock(PingCmd.class);
    when(client.pingCmd()).thenReturn(ping);
    CountDownLatch remoteStarted = new CountDownLatch(1);
    when(ping.exec()).thenAnswer(invocation -> {
      remoteStarted.countDown();
      new CountDownLatch(1).await();
      return null;
    });
    SandboxDockerClientFactory factory = mock(SandboxDockerClientFactory.class);
    when(factory.create()).thenReturn(client);
    DockerRunnerBackend backend = new DockerRunnerBackend(
        SandboxRunnerProperties.development(), factory, Duration.ofMillis(75));

    long startedAt = System.nanoTime();
    SandboxRunnerExecutionException failure = assertThrows(
        SandboxRunnerExecutionException.class,
        () -> backend.run(
            new RunSpec("print('never started')", "PYTHON", 92L),
            ignored -> {}));

    assertThat(remoteStarted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(2_000L);
    assertThat(failure.result().terminalStatus()).isEqualTo(SandboxTerminalStatus.FAILED);
    assertThat(failure.result().outputTruncated()).isTrue();
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    verify(client, atLeastOnce()).close();
  }

  @Test
  void expiredManagedSourceVolumeIsReapedAndDockerClientIsClosed() throws Exception {
    SandboxRunnerProperties properties =
        new SandboxRunnerProperties("tcp://runner:2376", "runsc", true, true, "/certs");
    DockerClient client = mock(DockerClient.class);
    ListContainersCmd containers = mock(ListContainersCmd.class);
    when(client.listContainersCmd()).thenReturn(containers);
    when(containers.withShowAll(true)).thenReturn(containers);
    when(containers.withLabelFilter(Map.of(DockerRunnerBackend.MANAGED_LABEL, "true")))
        .thenReturn(containers);
    when(containers.exec()).thenReturn(List.of());

    ListVolumesCmd volumes = mock(ListVolumesCmd.class);
    ListVolumesResponse response = mock(ListVolumesResponse.class);
    InspectVolumeResponse expired = mock(InspectVolumeResponse.class);
    when(client.listVolumesCmd()).thenReturn(volumes);
    when(volumes.withFilter("label", List.of(DockerRunnerBackend.MANAGED_LABEL + "=true")))
        .thenReturn(volumes);
    when(volumes.exec()).thenReturn(response);
    when(response.getVolumes()).thenReturn(List.of(expired));
    when(expired.getName()).thenReturn("devpath-sandbox-91-source");
    when(expired.getLabels()).thenReturn(DockerRunnerBackend.executionLabels(
        91L, Instant.parse("2026-08-16T00:00:30Z")));
    RemoveVolumeCmd remove = mock(RemoveVolumeCmd.class);
    when(client.removeVolumeCmd("devpath-sandbox-91-source")).thenReturn(remove);

    SandboxDockerClientFactory factory = mock(SandboxDockerClientFactory.class);
    when(factory.create()).thenReturn(client);
    DockerRunnerBackend backend = new DockerRunnerBackend(properties, factory);

    assertThat(backend.reapExpiredContainers(Instant.parse("2026-08-16T00:00:31Z")))
        .isEqualTo(1);
    verify(remove).exec();
    verify(client).close();
  }
}
