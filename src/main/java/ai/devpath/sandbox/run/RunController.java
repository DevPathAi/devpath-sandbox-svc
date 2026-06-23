package ai.devpath.sandbox.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/sandbox")
public class RunController {

  private static final int MAX_CODE_BYTES = 64 * 1024;

  private final SandboxRunService runService;
  private final long sseTimeoutMs;

  public RunController(SandboxRunService runService,
      @Value("${devpath.sandbox.sse-timeout-ms:60000}") long sseTimeoutMs) {
    this.runService = runService;
    this.sseTimeoutMs = sseTimeoutMs;
  }

  @PostMapping(path = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter run(@AuthenticationPrincipal Jwt jwt,
      @RequestBody SandboxRunRequest req) {
    validate(req);
    // Docker 등 runner 불가 시 SSE 시작 전에 503으로 끊어 세션·이벤트 찌꺼기를 만들지 않는다.
    if (!runService.isRunnerAvailable()) {
      throw new SandboxUnavailableException("Sandbox runner is not available");
    }
    long userId = Long.parseLong(jwt.getSubject());
    SseEmitter emitter = new SseEmitter(sseTimeoutMs);

    CompletableFuture.runAsync(() -> {
      try {
        runService.execute(userId, req, line -> sendLog(emitter, line));
        emitter.complete();
      } catch (Exception e) {
        emitter.completeWithError(e);
      }
    });

    return emitter;
  }

  private static void validate(SandboxRunRequest req) {
    if (req == null || req.code() == null || req.language() == null) {
      throw new IllegalArgumentException("code와 language는 필수입니다.");
    }
    if (req.code().getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
      throw new IllegalArgumentException("코드 크기 제한(64KB) 초과");
    }
    if (!req.language().matches("JAVA|NODE|PYTHON")) {
      throw new IllegalArgumentException("지원하지 않는 language: " + req.language());
    }
  }

  private void sendLog(SseEmitter emitter, String line) {
    try {
      emitter.send(SseEmitter.event().name("log").data(line));
    } catch (IOException e) {
      throw new IllegalStateException("SSE send failed", e);
    }
  }
}
