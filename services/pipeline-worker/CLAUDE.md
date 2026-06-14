# pipeline-worker CLAUDE.md

## 실행 명령어

```bash
cd services/pipeline-worker
./gradlew bootRun
./gradlew test
./gradlew test --tests "패키지.클래스명"
./gradlew build
```

## 패키지 구조

| 패키지 | 역할 |
|--------|------|
| `controller` | HTTP endpoint. 비즈니스 로직을 넣지 않는다. |
| `pipeline` | 수집 오케스트레이션. fetch → normalize → publish → checkpoint 흐름을 조합한다. |
| `collection` | 프로젝트별 수집 설정과 webhook payload → 수집 context 해석 경계. DB의 project/integration 정보를 조회한다. |
| `trigger` | backend 연동 완료 요청을 provider별 비동기 초기 수집으로 연결한다. |
| `webhook` | GitHub webhook 검증, 필터링, delivery 중복 처리, 비동기 수집 트리거. |
| `source.github` | GitHub API 수집, 정규화, rate limit. |
| `source.jira` | Jira API 수집, 정규화, rate limit. |
| `source.slack` | Slack API 수집, 정규화, rate limit. |
| `normalizer` | 여러 source가 공유하는 정규화 보조 유틸. 현재 `RefsExtractor` 유지. |
| `checkpoint` | DB `checkpoints` 테이블 기반 checkpoint 조회/갱신 경계. |
| `messaging` | RabbitMQ publish. |
| `config` | Spring/RabbitMQ/WebClient/webhook executor 설정. |
| `dto` | 요청/응답/event DTO. |
| `util` | 일반 유틸. |

## 주요 클래스

| 클래스 | 역할 |
|--------|------|
| `CollectionTriggerController` | `/api/v1/collect/{provider}` provider별 초기 수집 트리거 endpoint. |
| `RawDataController` | `/api/v1/raw/*` 디버그용 raw 샘플 endpoint. |
| `GitHubWebhookController` | GitHub webhook 수신 endpoint. |
| `PipelineService` | GitHub/Jira/Slack 증분 수집 실행. |
| `ProjectCollectionContext` | projectId와 provider별 수집 설정을 묶은 context. |
| `ProjectIntegrationService` | DB의 project/integration 정보를 조회해 webhook 수집 context와 GitHub token freshness 상태 또는 provider별 단일 연동을 resolve한다. |
| `ProjectIntegrationRepository` | `projects`, `integrations`, `github_installations` 테이블 SQL 접근을 담당한다. |
| `CollectionTriggerService` | project/provider 연동을 조회하고 `webhookTaskExecutor`에서 단일 provider 초기 수집을 실행한다. |
| `GitHubWebhookIntegrationResolution` | webhook 연동 조회 결과를 `READY`, `TOKEN_REFRESH_REQUIRED`, `NOT_FOUND`로 구분한다. |
| `GitHubInstallationTokenClient` | 토큰 갱신이 필요한 경우에만 backend 내부 API를 호출하고 갱신된 DB token을 다시 읽게 한다. |
| `GitHubWebhookService` | webhook 검증 이후 merged PR 필터, token freshness 확인, project context 조회, delivery claim, 비동기 수집 트리거. |
| `GitHubWebhookVerifier` | `X-Hub-Signature-256` HMAC-SHA256 검증. |
| `WebhookDeliveryService` | DB 기반 webhook delivery claim, 처리 상태 갱신, stale `IN_PROGRESS` 정리를 담당한다. |
| `WebhookDeliveryRepository` | `webhook_deliveries` 테이블 SQL 접근을 담당한다. |
| `CollectTriggerRequest` | `/api/v1/collect/{provider}` 요청 DTO. `projectId` UUID를 필수로 받는다. |
| `RawFetchRequest` | `/api/v1/raw/*` 요청 DTO. checkpoint 없이 샘플 fetch에 사용한다. |
| `*RawService` | provider별 외부 API raw 데이터 수집. |
| `*Normalizer` | raw 데이터를 `NormalizedEvent`로 변환. |
| `*RateLimiter` | provider별 API 요청 속도 제한. |
| `EventPublisher` | `NormalizedEvent`를 RabbitMQ에 발행. |
| `CheckpointService` | DB 기반 project/provider/cursor_key checkpoint snapshot 조회와 cursor 갱신을 담당한다. |
| `CheckpointRepository` | `checkpoints` 테이블 SQL 접근을 담당한다. |
| `CredentialCryptoService` | DB에 암호화 저장된 provider credential을 `security.credentials.key`로 복호화한다. |

## Endpoint

| Endpoint | 용도 | 응답 |
|----------|------|------|
| `POST /api/v1/webhook/github` | GitHub PR merge webhook 수신. `pull_request` + `action=closed` + `merged=true`만 수집 트리거. | `202`, `200`, `400`, `401`, `404`, `500` |
| `POST /api/v1/collect/{provider}` | backend 연동 완료 후 `github`, `jira`, `slack` 중 단일 provider 초기 수집을 비동기로 요청 | `202`, `400`, `404`, `500` |
| `POST /api/v1/raw/github` | `RawFetchRequest` 기반 GitHub raw 디버그, 타입별 1페이지 샘플. DB checkpoint를 사용하지 않는다. | raw payload |
| `POST /api/v1/raw/jira` | `RawFetchRequest` 기반 Jira raw 디버그, 기본 1페이지 샘플. DB checkpoint를 사용하지 않는다. | raw payload |
| `POST /api/v1/raw/slack` | `RawFetchRequest` 기반 Slack raw 디버그, 첫 채널 1페이지 샘플. DB checkpoint를 사용하지 않는다. | raw payload |

`RawFetchRequest`는 `credentials`, `projectKey`, `options`만 받으며 디버그 샘플 확인 용도다.

## 초기 수집 트리거 흐름

```text
backend integration connect commit
  -> POST /api/v1/collect/{provider} {projectId}
  -> ProjectIntegrationService로 해당 provider 연동 조회·credential 복호화
  -> webhookTaskExecutor에서 비동기 실행
  -> PipelineService.normalize{Provider}(...)
  -> RabbitMQ publish
  -> DB checkpoint 갱신
```

트리거 endpoint는 연동을 찾으면 수집 완료를 기다리지 않고 `202`를 반환한다. provider 연동이 없으면 `404`를 반환한다.
checkpoint 기반 증분 수집이므로 별도 delivery 중복 방지는 적용하지 않는다.

## Webhook 수집 흐름

```text
GitHub PR merge webhook
  -> GitHubWebhookController
  -> GitHubWebhookVerifier로 HMAC 검증
  -> pull_request / closed / merged=true 필터
  -> ProjectIntegrationService로 integration과 installation token freshness 조회
  -> token이 없거나 만료 5분 이내면 backend 내부 API로 token 갱신
  -> 갱신한 경우 ProjectIntegrationService로 DB integration 재조회
  -> WebhookDeliveryService.tryClaim(deliveryId, projectId)
  -> webhookTaskExecutor에서 비동기 실행
  -> PipelineService.collectIncremental(context)
  -> RabbitMQ publish
  -> CheckpointService로 DB checkpoint 갱신
  -> WebhookDeliveryService.markProcessed/markFailed
```

`ProjectIntegrationService`가 GitHub installation/repository 정보를 DB의 project/integration row와 매칭한다. 매칭되는 integration이 없으면 `404`를 반환한다.
installation token이 충분히 유효하면 backend를 호출하지 않는다. token이 없거나 만료 5분 이내면 `GitHubInstallationTokenClient`가 `X-Internal-Service-Token`으로 backend의 token 보장 API를 호출한 뒤 DB를 재조회한다. backend는 token 평문을 반환하지 않는다.
backend에 installation이 없으면 `404`, backend 호출 실패 또는 token 갱신 실패는 `500`으로 처리해 GitHub 재시도를 허용한다.
Jira/Slack 연동은 선택 항목이므로 credential 또는 external_ref가 잘못된 경우 해당 provider를 건너뛰고 가능한 provider 수집은 진행한다.
`webhookTaskExecutor`가 작업을 받을 수 없으면 `IN_PROGRESS` claim을 해제해 GitHub 재시도가 다시 claim할 수 있게 한다.
애플리케이션 시작 시 `app.webhook.delivery.stale-in-progress-timeout`보다 오래된 `IN_PROGRESS` delivery는 `FAILED`로 정리한다.
상세 계획은 `docs/db-transition-plan.md`를 참고한다.

## RabbitMQ 라우팅

| Source | Routing key |
|--------|-------------|
| GitHub | `event.github` |
| Jira | `event.jira` |
| Slack | `event.slack` |

Exchange: `history.exchange` / Queue: `history.events`

## Rate Limiting

- **GitHub**: 기본 300ms 고정 딜레이. `X-RateLimit-Remaining`이 임계값 이하이면 `X-RateLimit-Reset`까지 대기.
- **Slack**: endpoint별 고정 딜레이 (`conversations.list` / `history` / `replies`).
- **Jira**: 호출당 200ms 고정 딜레이.

## Checkpoint

- 현재는 DB `checkpoints` 테이블을 사용한다.
- 재시작 시 마지막 수집 시각 이후 데이터만 수집해 누락을 방지하고 중복을 최소화한다.
- checkpoint 기준은 `Instant.now()`가 아니라 이벤트 실제 발생 시각인 `occurredAt`이다.
- GitHub는 타입별 checkpoint를 사용한다: `github/github_commits`, `github/github_pull_requests`, `github/github_issues`.
- Jira는 `jira/jira_updated`, Slack은 `slack/slack_messages` cursor를 사용한다.
- GitHub PR checkpoint는 commit 처리 성공 후 갱신해 재시작 시 `sha → prNumber` 매핑을 다시 만들 수 있게 한다.
- Slack은 전체 실행 중 최대 `occurredAt`을 마지막에 한 번 갱신한다.
- Jira는 `updated` 기준으로 수집하고, 페이지 단위 publish 후 checkpoint를 갱신한다.
- checkpoint는 project 단위로 저장한다.
- cursor 갱신은 기존 값보다 과거 시각으로 되돌아가지 않게 저장한다.

## 설정 파일

`src/main/resources/application.yaml`에서 다음을 설정한다.

- RabbitMQ 연결 정보
- PostgreSQL datasource
- credential 복호화 키 (`security.credentials.key`)
- backend 내부 API URL (`backend.url`)
- 내부 서비스 인증 token (`security.internal-service.token`)
- GitHub/Jira/Slack base URL
- rate limit 값
- GitHub webhook secret
- webhook executor 종료 대기 시간
- stale `IN_PROGRESS` webhook delivery 정리 기준 시간

사용자/프로젝트별 credential은 `application.yaml`에 두지 않는다. DB의 project integration 정보에서 조회하고 `security.credentials.key`로 복호화한다.
GitHub/Slack credential은 Bearer 토큰으로 사용한다. Jira credential은 `JiraRawService`에서 Basic/Bearer 형식을 해석한다.

로컬 실행과 배포 시 다음 환경변수를 설정한다.

- `BACKEND_CREDENTIAL_KEY`: backend와 동일한 credential 암호화 키. 필수.
- `INTERNAL_SERVICE_TOKEN`: backend와 동일한 내부 서비스 공유 token. 필수.
- `BACKEND_URL`: backend 내부 API 주소. 기본값은 `http://localhost:8080`이며 배포 환경에서는 명시한다.

GitHub App private key는 pipeline-worker에 설정하지 않는다. token 발급은 backend가 전담한다.

## 규칙 및 주의사항

- Controller에는 수집/정규화/publish/checkpoint 조합 로직을 넣지 않는다.
- 전체 수집 흐름 조합은 `pipeline.PipelineService`에서 처리한다.
- provider별 API 호출/정규화/rate limit은 `source.{provider}` 패키지 안에서 처리한다.
- GitHub merge commit은 `GitHubNormalizer`에서 필터링한다.
- GitHub PR 수집은 `/pulls?state=closed` + 클라이언트 `merged_at != null` 필터 방식이다.
- `/api/v1/raw/*` endpoint는 디버그용 샘플이다. 전체 수집 용도로 사용하지 않는다.
