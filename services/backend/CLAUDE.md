## 실행 명령어

```bash
cd services/backend
./gradlew bootRun
./gradlew test
./gradlew test --tests "패키지.클래스명"
./gradlew build
```

## 패키지 구조

패키지는 기능 단위로 나눈다. `auth`, `github`, `project`, `integration`, `conversation`, `graph` 아래에 `controller/service/repository/domain/dto`를 둔다(기능별로 일부 계층은 생략한다). `graph`는 자체 저장소 없이 ai-engine 그래프 조회를 프록시하고, `jira`는 Jira 연동 검증용 client/dto만 둔다. 전역 코드는 `common`, `config`, `security`, pipeline 공유 테이블은 `shared`에 둔다.

## 규칙 및 주의사항

- 다른 기능의 Repository를 직접 주입하지 말고 Service를 통해 접근한다.
- Controller에는 비즈니스 로직을 두지 않는다.
- 인증 사용자 ID를 받는 비공개 API/service는 비즈니스 처리 전에 `UserService.getActiveUser()` 또는 이를 호출하는 상위 service를 통해 active user를 검증한다.
- soft-deleted user는 grace period 복구 대상일 수 있지만, 복구 전에는 비공개 API 접근과 refresh token 재발급을 허용하지 않는다.
- DB 스키마는 Flyway migration으로 관리하고 JPA `ddl-auto`는 `validate`를 사용한다.
- 기능 PR마다 필요한 migration을 추가한다.
- main에 머지된 migration 파일은 수정하지 말고 새 migration으로 변경한다.

## Pipeline Worker 연동

- `PipelineWorkerConfig`는 `pipeline.worker.url` 기반 `pipelineWorkerRestClient`와 connect/read timeout을 구성한다.
- `PipelineWorkerClient`는 provider 연동 커밋 후 `/api/v1/collect/{provider}`에 `projectId`만 전달하며, 트리거 실패를 연동 성공과 분리해 로그만 남긴다.

## AI Engine 연동

- `AiEngineConfig`는 `ai.engine.url` 기반 `aiEngineRestClient`를 구성한다.
- 그래프 데이터의 단일 소유자는 ai-engine(Neo4j)다. backend는 인가를 통과시킨 뒤 조회·삭제를 프록시만 한다.
  - `AiEngineGraphClient`: `GET /graph/overview`(`project_id` 스코프), `DELETE /graph/projects/{projectId}`(프로젝트 삭제 시 그래프 cascade, 멱등), `triggerBuild` → `POST /graph/build?project_id=&verify=`(Layer 4 빌드를 프로젝트 단위로 트리거, 202 + `GraphBuildStatusResponse`), `fetchBuildStatus` → `GET /graph/build/status?project_id=`(빌드 상태 폴링). ai-engine 호출 실패는 `BadGatewayException`(502)으로 변환한다.
  - 그래프 재구축은 `POST /api/v1/projects/{projectId}/graph/build?verify=`(202)로 노출한다(`GraphService.buildProjectGraph`). `verify=true`면 방안 D(LLM 검증). ai-engine 빌드가 프로젝트 단위 비동기라 `projectId`는 인가 게이트이자 실제 빌드 대상이고, 트리거는 즉시 202로 반환된 뒤 `GET .../graph/build/status`(`GraphService.getBuildStatus`)로 완료를 폴링한다.
  - `AiEngineQueryClient`: 대화 질의 `POST /query`, 누적 요약 갱신 `POST /query/summary`. 질의 실패 시 예외 대신 fallback 답변을 반환해 대화 흐름을 유지한다.
- 모든 ai-engine 호출은 `projectId`로 스코프해 다른 프로젝트 데이터 인용을 차단한다.

## 대화(conversation) 처리

- `MessageService.addMessage`는 트랜잭션을 2단계로 분리한다: (1) 사용자 메시지 저장, (2) ai-engine 질의(트랜잭션 밖) 후 assistant 응답 저장. 느린 AI 질의 중 DB 커넥션 점유를 피하고, 질의 실패와 무관하게 사용자 메시지를 보존하기 위함이다.
- 최근 `MAX_HISTORY_TURNS`(5) 완성 턴만 history로 ai-engine에 전달하고, 그보다 오래된 턴은 running summary로 누적 압축한다. fallback/blank로 끝난 턴은 history·요약에서 제외한다.
- running summary 갱신은 version 기반 낙관적 충돌 처리로, 실패하거나 충돌해도 현재 질문 응답을 막지 않는다.
- 직전 정상 응답의 `structured.evidence`에서 후속 질문 대상 식별용 prior evidence를 추출해 함께 전달한다.

## 내부 서비스 API

- `/api/v1/internal/**`는 사용자 JWT가 아니라 `X-Internal-Service-Token` 헤더로 인증한다.
- `InternalServiceAuthenticationFilter`는 `security.internal-service.token`과 요청 헤더를 timing-safe 방식으로 비교한다.
- `POST /api/v1/internal/github/installations/{installationId}/token`은 GitHub installation access token이 없거나 만료 임박한 경우 갱신해 DB 캐시를 보장하고 `204`를 반환한다. 토큰 평문은 응답하지 않는다.
- backend와 pipeline-worker에는 동일한 `INTERNAL_SERVICE_TOKEN`을 배포해야 한다.
- GitHub App private key는 backend에만 두고 pipeline-worker와 공유하지 않는다.

## 주석 규칙

### 함수 주석

- 주요 함수, public 함수, 복잡한 private 함수에는 역할을 명사형으로 짧게 작성한다.
  - 예: `// refresh token 1회용 rotation (사용된 토큰 폐기 후 재발급)`, `// 활성(미탈퇴) 사용자 조회`
- 함수 내부 구현을 반복 설명하지 않는다.
- getter/setter, 단순 위임 함수(Controller 메서드 포함), 이름만으로 역할이 명확한 함수에는 주석을 달지 않는다.
- 외부 시스템과 공유하는 테이블의 엔티티, 비직관적 설계가 있는 클래스에는 클래스 주석으로 맥락을 남긴다.
  - 예: `// pipeline-worker 수집 진행 커서 — (project, provider, cursor_key) 복합키 공유 테이블`

### 코드 내부 주석

- "무엇을 하는지"보다 "왜 이렇게 처리하는지"를 우선 설명한다. 다음 지점에만 짧게 추가한다.
  - 동시성 처리: 비관적 잠금, double-checked locking, `ON CONFLICT DO NOTHING` 후 재조회 폴백 등
  - 트랜잭션 설계: 외부 API 호출을 트랜잭션 밖으로 분리하는 이유, `Propagation.MANDATORY` 사용 이유, batch 단위 트랜잭션 분리 등
  - 보안 처리: SSRF 방어, 타이밍 공격 방지 비교, hash 저장, 방어적 복사 등
  - 외부 API 특성: 오류 응답을 특정 HTTP 상태로 변환하는 이유, 비표준 응답 처리(예: Slack은 실패도 200 응답) 등
- 어노테이션이나 코드가 이미 말하는 내용을 반복하는 라인 주석은 추가하지 않는다.
- 코드만으로 단정할 수 없는 이유는 추측해서 적지 않는다. 잘못된 주석은 없는 것보다 나쁘다.

### 주석을 생략하는 곳

- 단순 CRUD service 메서드, Spring Data 파생 쿼리, DTO/record, 표준 패턴(enum converter, `@Embeddable` 복합키, 단순 빈 등록 config)

