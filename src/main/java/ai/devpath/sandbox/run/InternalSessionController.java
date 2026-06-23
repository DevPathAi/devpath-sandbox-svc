package ai.devpath.sandbox.run;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 서비스 간 내부 조회(게이트웨이 미경유). ai-svc 리뷰 워커가 코드+결과를 가져온다(슬라이스 #6 D-7). */
@RestController
@RequestMapping("/internal/sandbox/sessions")
public class InternalSessionController {

  private final SandboxSessionRepository sessions;

  public InternalSessionController(SandboxSessionRepository sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/{id}")
  public SandboxSessionView get(@PathVariable long id) {
    return sessions.findById(id)
        .map(SandboxSessionView::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
