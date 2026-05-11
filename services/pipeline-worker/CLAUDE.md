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
| `collection` | 프로젝트별 수집 설정과 webhook payload → 수집 context 해석 경계. DB 전환 시 이 영역이 project/integration 조회를 담당한다. |
| `webhook` | GitHub webhook 검증, 필터링, delivery 중복 처리, 비동기 수집 트리거. |
| `source.github` | GitHub API 수집, 정규화, rate limit. |
| `source.jira` | Jira API 수집, 정규화, rate limit. |
| `source.slack` | Slack API 수집, 정규화, rate limit. |
| `normalizer` | 여러 source가 공유하는 정규화 보조 유틸. 현재 `RefsExtractor` 유지. |
| `checkpoint` | 파일 기반 checkpoint 관리. |
| `messaging` | RabbitMQ publish. |
| `config` | Spring/RabbitMQ/WebClient/webhook executor 설정. |
| `dto` | 요청/응답/event DTO. |
| `util` | 일반 유틸. |

## 주요 클래스

| 클래스 | 역할 |
|--------|------|
| `PipelineController` | `/api/v1/normalize/*` 수동 증분 수집 endpoint. |
| `RawDataController` | `/api/v1/raw/*` 디버그용 raw 샘플 endpoint. |
| `GitHubWebhookController` | GitHub webhook 수신 endpoint. |
| `PipelineService` | GitHub/Jira/Slack 증분 수집 실행. |
| `ProjectCollectionContext` | projectId와 provider별 수집 설정을 묶은 context. |
| `ProjectIntegrationResolver` | GitHub webhook payload를 프로젝트 수집 context로 해석하는 경계. 현재 기본 구현은 noop. |
| `GitHubWebhookService` | webhook 검증 이후 merged PR 필터, project context 조회, delivery claim, 비동기 수집 트리거. |
| `GitHubWebhookVerifier` | `X-Hub-Signature-256` HMAC-SHA256 검증. |
| `WebhookDeliveryStore` | webhook delivery 중복 처리 계약. 현재 기본 구현은 파일 기반. DB 전환 시 삭제 가능. |
| `FileWebhookDeliveryStore` | `webhook-deliveries.json` 파일 기반 임시 delivery store. 재시작 시 `IN_PROGRESS` 항목은 stale로 보고 제거한다. |
| `*RawService` | provider별 외부 API raw 데이터 수집. |
| `*Normalizer` | raw 데이터를 `NormalizedEvent`로 변환. |
| `*RateLimiter` | provider별 API 요청 속도 제한. |
| `EventPublisher` | `NormalizedEvent`를 RabbitMQ에 발행. |
| `FileCheckpointManager` | `checkpoint.json` 파일 기반 checkpoint 관리. |

## Endpoint

| Endpoint | 용도 | 응답 |
|----------|------|------|
| `POST /api/v1/webhook/github` | GitHub PR merge webhook 수신. `pull_request` + `action=closed` + `merged=true`만 수집 트리거. | `202`, `200`, `401`, `404` |
| `POST /api/v1/normalize/github` | GitHub 증분 수집 → 정규화 → RabbitMQ 발행 | `202 {"queued": N}` |
| `POST /api/v1/normalize/jira` | Jira 증분 수집 → 정규화 → RabbitMQ 발행 | `202 {"queued": N}` |
| `POST /api/v1/normalize/slack` | Slack 증분 수집 → 정규화 → RabbitMQ 발행 | `202 {"queued": N}` |
| `POST /api/v1/raw/github` | GitHub raw 디버그, 타입별 1페이지 샘플 | raw payload |
| `POST /api/v1/raw/jira` | Jira raw 디버그, 기본 1페이지 샘플 | raw payload |
| `POST /api/v1/raw/slack` | Slack raw 디버그, 첫 채널 1페이지 샘플 | raw payload |

## Webhook 수집 흐름

```text
GitHub PR merge webhook
  -> GitHubWebhookController
  -> GitHubWebhookVerifier로 HMAC 검증
  -> pull_request / closed / merged=true 필터
  -> ProjectIntegrationResolver로 ProjectCollectionContext 조회
  -> WebhookDeliveryStore.tryClaim(deliveryId)
  -> webhookTaskExecutor에서 비동기 실행
  -> PipelineService.collectIncremental(context)
  -> RabbitMQ publish
```

현재 DB가 없으므로 `ProjectIntegrationResolver` 기본 구현은 noop이며, project를 찾지 못하면 `404`를 반환한다.
DB 전환 시에는 project/integration 조회 구현을 추가한다. 상세 계획은 `docs/db-transition-plan.md`를 참고한다.

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

- 현재는 파일 기반 `checkpoint.json`을 사용한다.
- 재시작 시 중복 수집 방지: 마지막 수집 시각 이후 데이터만 수집한다.
- checkpoint 기준은 `Instant.now()`가 아니라 이벤트 실제 발생 시각인 `occurredAt`이다.
- GitHub는 타입별 checkpoint를 사용한다: `commitsScannedAt`, `pullRequestsScannedAt`, `issuesScannedAt`.
- GitHub PR checkpoint는 commit 처리 성공 후 갱신해 재시작 시 `sha → prNumber` 매핑을 다시 만들 수 있게 한다.
- Slack은 전체 실행 중 최대 `occurredAt`을 마지막에 한 번 갱신한다.
- Jira는 `updated` 기준으로 수집하고, 페이지 단위 publish 후 checkpoint를 갱신한다.
- DB 전환 후에는 project 단위 checkpoint 저장으로 바꾸는 것이 권장된다.

## 설정 파일

`src/main/resources/application.yaml`에서 다음을 설정한다.

- RabbitMQ 연결 정보
- GitHub/Jira/Slack base URL
- rate limit 값
- checkpoint 파일 경로
- GitHub webhook secret
- webhook executor 종료 대기 시간
- 임시 파일 기반 webhook delivery store 설정
- 임시 noop project integration resolver 설정

사용자/프로젝트별 credential은 `application.yaml`에 두지 않는다. DB 전환 후 project integration 정보에서 조회한다.

## 규칙 및 주의사항

- Controller에는 수집/정규화/publish/checkpoint 조합 로직을 넣지 않는다.
- 전체 수집 흐름 조합은 `pipeline.PipelineService`에서 처리한다.
- provider별 API 호출/정규화/rate limit은 `source.{provider}` 패키지 안에서 처리한다.
- GitHub merge commit은 `GitHubNormalizer`에서 필터링한다.
- GitHub PR 수집은 `/pulls?state=closed` + 클라이언트 `merged_at != null` 필터 방식이다.
- `/api/v1/raw/*` endpoint는 디버그용 샘플이다. 전체 수집 용도로 사용하지 않는다.
