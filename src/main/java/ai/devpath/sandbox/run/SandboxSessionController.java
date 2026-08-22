package ai.devpath.sandbox.run;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated owner-only recovery for an accepted Sandbox session. */
@RestController
@RequestMapping("/sandbox/sessions")
public class SandboxSessionController {

  private final SandboxSessionRepository sessions;

  public SandboxSessionController(SandboxSessionRepository sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/{id}")
  public ResponseEntity<SandboxSessionRecoveryView> get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long id) {
    long userId = Long.parseLong(jwt.getSubject());
    SandboxSessionRecoveryView view = sessions.findByIdAndUserId(id, userId)
        .map(SandboxSessionRecoveryView::from)
        .orElseThrow(() -> new SessionNotFoundException("sandbox session not found"));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
  }
}
