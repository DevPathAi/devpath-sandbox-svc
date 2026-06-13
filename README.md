# devpath-sandbox-svc

**DevPath AI** Sandbox Runner — 사용자 코드를 격리 실행하는 서비스입니다.

## 담당 도메인

| 모듈 | 역할 |
|------|------|
| runner | Docker Pool + gVisor(runsc) 격리 실행 |
| submission | 과제 제출 접수 → 실행 → 결과 회수 |

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

## 개발 규칙

- Git 규칙: [documents/09_Git_규칙_정의서](https://github.com/DevPathAi/documents/blob/main/09_Git_규칙_정의서.md)
- 워크플로우 현황: `docs/project-management/` → [workflow-dashboard](https://devpathai.github.io/workflow-dashboard/)
