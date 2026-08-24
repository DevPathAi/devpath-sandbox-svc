package ai.devpath.sandbox.run;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/sandbox")
public class RunController {

  static final String SESSION_HEADER = "X-Sandbox-Session-Id";
  static final String EVENT_VERSION_HEADER = "X-Sandbox-Event-Version";
  private static final int MAX_CODE_BYTES = 64 * 1024;
  private static final long MAX_JAVASCRIPT_SAFE_INTEGER = 9_007_199_254_740_991L;

  private final SandboxRunService runService;
  private final SandboxHeartbeatScheduler heartbeatScheduler;
  private final SandboxReleaseFaultRegistry releaseFaults;
  private final long sseTimeoutMs;

  public RunController(
      SandboxRunService runService,
      SandboxHeartbeatScheduler heartbeatScheduler,
      SandboxReleaseFaultRegistry releaseFaults,
      @Value("${devpath.sandbox.sse-timeout-ms:60000}") long sseTimeoutMs) {
    this.runService = runService;
    this.heartbeatScheduler = heartbeatScheduler;
    this.releaseFaults = releaseFaults;
    this.sseTimeoutMs = sseTimeoutMs;
  }

  @PostMapping(path = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> run(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody SandboxRunRequest request,
      @RequestHeader(name = EVENT_VERSION_HEADER, required = false) String eventVersion,
      @RequestHeader(name = "X-Candidate-Spec-Sha256", required = false) String candidate,
      @RequestHeader(name = "X-Release-Run-Key", required = false) String releaseRunKey) {
    validate(request);
    long userId = Long.parseLong(jwt.getSubject());
    runService.assertCanAdmit(userId);
    if (!runService.isRunnerAvailable()) {
      throw new SandboxUnavailableException("Sandbox runner is not available");
    }

    SseEmitter emitter = new SseEmitter(sseTimeoutMs);
    boolean terminalEventsEnabled = "2".equals(eventVersion);
    SandboxRunDelivery delivery = heartbeatScheduler.wrap(
        new SseSandboxRunDelivery(emitter, terminalEventsEnabled));
    AcceptedSandboxRun accepted;
    try {
      accepted = runService.start(
          userId,
          request,
          delivery,
          releaseFaults.consumeForRun(candidate, releaseRunKey));
    } catch (RuntimeException admissionFailure) {
      delivery.complete();
      throw admissionFailure;
    }

    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .cacheControl(CacheControl.noStore())
        .header(SESSION_HEADER, String.valueOf(accepted.sessionId()))
        .header("Access-Control-Expose-Headers", SESSION_HEADER)
        .header("X-Accel-Buffering", "no")
        .body(emitter);
  }

  private static void validate(SandboxRunRequest request) {
    if (request == null || request.code() == null || request.language() == null) {
      throw new IllegalArgumentException("code와 language는 필수입니다.");
    }
    if (request.code().isBlank()) {
      throw new IllegalArgumentException("code는 비어 있을 수 없습니다.");
    }
    if (request.code().getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
      throw new IllegalArgumentException("코드 크기 제한(64KB) 초과");
    }
    if (!request.language().matches("JAVA|NODE|PYTHON")) {
      throw new IllegalArgumentException("지원하지 않는 language: " + request.language());
    }
    validateOptionalId("contentId", request.contentId());
    validateOptionalId("codeBlockId", request.codeBlockId());
  }

  private static void validateOptionalId(String name, Long value) {
    if (value != null && (value < 1 || value > MAX_JAVASCRIPT_SAFE_INTEGER)) {
      throw new IllegalArgumentException(name + "는 양의 JavaScript-safe 정수여야 합니다.");
    }
  }
}
