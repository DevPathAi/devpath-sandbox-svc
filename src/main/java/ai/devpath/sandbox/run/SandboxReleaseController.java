package ai.devpath.sandbox.run;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/release/sandbox/{candidate}/{runKey}")
@ConditionalOnProperty(name = "devpath.release.enabled", havingValue = "true")
public class SandboxReleaseController {
  private final SandboxReleaseFaultRegistry release;
  private final SandboxReleaseStaleFixtureService stale;

  public SandboxReleaseController(
      SandboxReleaseFaultRegistry release,
      SandboxReleaseStaleFixtureService stale) {
    this.release = release;
    this.stale = stale;
  }

  @PostMapping("/commands/{command}")
  public Map<String, Object> command(
      @PathVariable String candidate,
      @PathVariable String runKey,
      @PathVariable String command,
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> safeBody = body == null ? Map.of() : body;
    Map<String, Object> response = new LinkedHashMap<>();
    if (command.startsWith("next-run-")) {
      release.arm(candidate, runKey, command);
      response.put("accepted", true);
      return response;
    }
    if ("clear-faults".equals(command)) {
      release.clear(candidate, runKey);
      response.put("accepted", true);
      return response;
    }
    String staleStatus = switch (command) {
      case "seed-stale-allocating" -> "ALLOCATING";
      case "seed-stale-running" -> "RUNNING";
      default -> throw new IllegalArgumentException("unsupported Sandbox release command");
    };
    Object rawUserId = safeBody.get("user_id");
    if (!(rawUserId instanceof Number number)) {
      throw new IllegalArgumentException("release fixture user id is required");
    }
    var seeded = stale.seed(candidate, runKey, number.longValue(), staleStatus);
    response.put("accepted", seeded.passed());
    response.put("session_id", seeded.sessionId());
    return response;
  }

  @GetMapping("/checkpoints/{checkpoint}")
  public Map<String, Object> checkpoint(
      @PathVariable String candidate,
      @PathVariable String runKey,
      @PathVariable String checkpoint) {
    return Map.of("passed", release.checkpoint(candidate, runKey, checkpoint));
  }
}
