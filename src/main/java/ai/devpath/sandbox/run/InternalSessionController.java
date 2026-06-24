package ai.devpath.sandbox.run;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 서비스 간 내부 조회(게이트웨이 미경유). ai-svc가 코드+결과(슬라이스 #6 D-7)·멘토 context(슬라이스 #7)를 가져온다. */
@RestController
@RequestMapping("/internal/sandbox/sessions")
public class InternalSessionController {

  private static final int MAX_LIMIT = 20;

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

  /** 사용자별 최근 실행(started_at DESC). 멘토 context_snapshot 주입용(슬라이스 #7 빌드 D 소비). */
  @GetMapping("/recent")
  public List<SandboxSessionView> recent(
      @RequestParam long userId,
      @RequestParam(defaultValue = "5") int limit) {
    int clamped = Math.min(Math.max(limit, 1), MAX_LIMIT);
    return sessions.findByUserIdOrderByStartedAtDesc(userId, PageRequest.of(0, clamped))
        .stream()
        .map(SandboxSessionView::from)
        .toList();
  }
}
