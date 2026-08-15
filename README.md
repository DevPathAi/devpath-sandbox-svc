# devpath-sandbox-svc

**DevPath AI** Sandbox Runner — 사용자 코드를 격리 실행하는 서비스입니다.

## 담당 도메인

| 모듈 | 역할 |
|------|------|
| run | Docker Pool + gVisor(runsc) 격리 실행, SSE 실행 로그(`POST /sandbox/run`) — 구현됨 |

**아키텍처 원칙**: 보안상 코어와 무조건 분리된 격리 서비스입니다. 실행 컨테이너는 네트워크 차단 + 리소스 제한 + gVisor 샌드박스로 구동합니다.

## 구성

- Spring Boot 4.0.x · Java 21 · Gradle (Kotlin DSL)
- [devpath-svc-template](https://github.com/DevPathAi/devpath-svc-template) 기반
- 실행 환경: Docker + gVisor (runsc 런타임)

## 빌드 / 실행

```bash
./gradlew build
./gradlew bootRun    # 기본 포트 8080
```

로컬에서 gVisor 없이 개발할 때는 일반 Docker 런타임으로 폴백합니다 (프로덕션은 runsc 필수).

## 실행 세션과 복구 계약

`POST /sandbox/run`은 전역/사용자별 admission을 통과한 뒤 `ALLOCATING` 세션을 먼저
커밋합니다. 응답의 `X-Sandbox-Session-Id`와 기존 numeric `session` SSE 이벤트는 같은
안정적인 ID를 제공합니다. 이 ID를 받은 인증 사용자는
`GET /sandbox/sessions/{sessionId}`로 `ALLOCATING`, `RUNNING`, terminal 상태와 저장된
출력 절단 여부를 복구할 수 있습니다.

새 소비자는 요청 헤더 `X-Sandbox-Event-Version: 2`를 보내 terminal `result` 이벤트를
opt-in합니다. 기존 소비자는 numeric `session`과 `log` 이벤트를 그대로 받고 JSON result를
로그로 오인하지 않습니다. terminal 상태는 `COMPLETED`, `FAILED`, `KILLED`, `TIMED_OUT`이며,
SSE 연결 종료는 실행 상태가 아닙니다.

현재 요청 계약에는 `clientRunId`가 없습니다. 따라서 응답 헤더나 numeric session 이벤트를
받기 전에 연결이 사라진 accepted request는 클라이언트 요청과 **cannot be correlated** 합니다.
이 경우를 멱등 복구됐다고 표현하면 안 됩니다. 같은 사용자의 즉시 재시도는 원래 실행이
활성인 동안 `SANDBOX_BUSY`를 받을 수 있고, session ID를 하나라도 받은 경우에만 owner GET
복구가 가능합니다.

출력은 stdout/stderr 합산 UTF-8 256 KiB, SSE 이벤트당 16 KiB로 제한됩니다. 실행과 terminal
outbox 저장은 SSE 전송과 독립적이며, 느리거나 끊긴 클라이언트는 실행을 취소하지 않습니다.

## 개발 규칙

- Git 규칙: [documents/09_Git_규칙_정의서](https://github.com/DevPathAi/documents/blob/main/09_Git_규칙_정의서.md)
- 워크플로우 현황: `docs/project-management/` → [workflow-dashboard](https://devpathai.github.io/workflow-dashboard/)
