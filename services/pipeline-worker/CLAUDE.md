# pipeline-worker CLAUDE.md

## 실행 명령어

```bash
cd services/pipeline-worker
./gradlew bootRun
./gradlew test
./gradlew test --tests "패키지.클래스명"
./gradlew build
```

## 내부 구조

| 클래스 | 역할 |
|--------|------|
| `RawDataController` | `/api/v1/raw/*` 디버그용 raw 수집 엔드포인트 |
| `PipelineController` | `/api/v1/normalize/*` 정규화·발행 엔드포인트 |
| `PipelineService` | fetch → normalize → publish → checkpoint 흐름 총괄 |
| `*RawService` | 외부 API 원시 데이터 수집 |
| `*Normalizer` | raw → `NormalizedEvent` 변환 |
| `*RateLimiter` | 소스별 요청 속도 제한 |
| `RefsExtractor` | 텍스트에서 Jira key·PR number 정규식 추출 |
| `EventPublisher` | `NormalizedEvent` → RabbitMQ 발행 |
| `FileCheckpointManager` | `checkpoint.json`으로 마지막 수집 시각 관리 |
| `JiraDateUtils` | Jira 날짜 문자열 파싱·JQL 포맷 변환 유틸 |

## Endpoint

| 엔드포인트 | 용도 | 응답 |
|-----------|------|------|
| `POST /api/v1/normalize/github` | GitHub 수집 → 정규화 → RabbitMQ 발행 | `202 {"queued": N}` |
| `POST /api/v1/normalize/jira` | Jira 수집 → 정규화 → RabbitMQ 발행 | `202 {"queued": N}` |
| `POST /api/v1/normalize/slack` | Slack 수집 → 정규화 → RabbitMQ 발행 | `202 {"queued": N}` |
| `POST /api/v1/raw/github` | GitHub raw 디버그, 타입별 1페이지 샘플 | raw payload |
| `POST /api/v1/raw/jira` | Jira raw 디버그, 기본 1페이지 | raw payload |
| `POST /api/v1/raw/slack` | Slack raw 디버그 | raw payload |

## RabbitMQ 라우팅 키

| 소스 | 라우팅 키 |
|------|-----------|
| GitHub | `event.github` |
| Jira | `event.jira` |
| Slack | `event.slack` |

Exchange: `history.exchange` / Queue: `history.events`

## Rate Limiting 방식

- **GitHub**: 평상시 300ms 고정 딜레이, `X-RateLimit-Remaining` ≤ 10이면 `X-RateLimit-Reset` 시각까지 동적 대기
- **Slack**: 엔드포인트별 고정 딜레이 (`conversations.list` 3,000ms / `history`·`replies` 1,200ms)
- **Jira**: 호출당 200ms 고정 딜레이

## Checkpoint

- 재시작 시 중복 수집 방지: 마지막 수집 시각 이후 데이터만 수집한다.
- 체크포인트 기준: `Instant.now()`가 아닌 이벤트 실제 발생 시각인 `occurredAt` 기준으로 갱신한다.
- GitHub는 타입별 독립 체크포인트를 사용한다: `commitsScannedAt`, `pullRequestsScannedAt`, `issuesScannedAt`.
- GitHub `occurredAt` 기준: Commit은 raw `commit.committer.date`, PR은 raw `merged_at`, Issue는 raw `updated_at`.
- GitHub normalize 경로는 PR/Commit/Issue를 페이지 단위로 처리한다. PR checkpoint는 commit 처리 성공 후 갱신해 재시작 시 `sha → prNumber` 매핑을 다시 만들 수 있게 한다.
- Slack은 `lastScannedAt` 단일 체크포인트를 사용한다. 루트 메시지가 checkpoint 이전이라도 `latest_reply`가 이후면 스레드 reply를 수집한다.
- Jira는 `lastScannedAt` 단일 체크포인트를 사용한다. `created`가 아닌 `updated` 기준으로 필터링하고, 페이지 단위 publish 후 checkpoint를 갱신한다.
- checkpoint 파일은 `.tmp` 파일에 쓴 뒤 `Files.move(ATOMIC_MOVE)`로 교체한다.

수집 전략 상세는 `docs/data-collection.md`를 참고한다.

## 설정 파일

`src/main/resources/application.yaml`에서 RabbitMQ 연결, GitHub/Jira/Slack base URL, rate limit 임계값, checkpoint 파일 경로, `app.jira.max-pages-per-run`을 설정한다.

## 규칙 및 주의사항

- 새 데이터 소스 추가 시 `*RawService` + `*Normalizer` + `*RateLimiter` 세트로 구현한다.
- Controller에는 비즈니스 오케스트레이션 로직을 두지 않는다. fetch/normalize/publish/checkpoint 조합은 `PipelineService`에서 처리한다.
- GitHub merge commit은 `GitHubNormalizer`에서 필터링한다.
- GitHub PR 수집은 `/pulls?state=closed` + 클라이언트 `merged_at != null` 필터 방식이다. Search API(`/search/issues`)는 사용하지 않는다.
- `/api/v1/raw/*` 엔드포인트는 디버그용으로 유지한다. GitHub raw는 전체 수집이 아니라 필드 확인용 1페이지 샘플이다.
