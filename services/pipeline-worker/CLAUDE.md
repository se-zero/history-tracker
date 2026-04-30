# pipeline-worker CLAUDE.md


## 실행 명령어

```bash
cd services/pipeline-worker
./gradlew bootRun
./gradlew test         
./gradlew test --tests "패키지.클래스명"   
./gradlew build
```
> 현재는 테스트 코드가 없음

## 내부 구조

| 클래스 | 역할 |
|--------|------|
| `RawDataController` | `/api/v1/raw/*` 디버그용 raw 수집 엔드포인트 |
| `PipelineController` | `/api/v1/normalize/*` 정규화·발행 엔드포인트 |
| `PipelineService` | fetch → normalize → publish → checkpoint 흐름 총괄 |
| `*RawService` | 외부 API 원시 데이터 수집 |
| `*Normalizer` | raw → NormalizedEvent 변환 |
| `*RateLimiter` | 소스별 요청 속도 제한 |
| `RefsExtractor` | 텍스트에서 Jira key·PR number 정규식 추출 |
| `EventPublisher` | NormalizedEvent → RabbitMQ 발행 |
| `FileCheckpointManager` | checkpoint.json으로 마지막 수집 시각 관리 |
| `JiraDateUtils` | Jira 날짜 문자열 파싱·JQL 포맷 변환 유틸 |

## RabbitMQ 라우팅 키

| 소스 | 라우팅 키 |
|------|-----------|
| GitHub | `event.github` |
| Jira | `event.jira` |
| Slack | `event.slack` |

Exchange: `history.exchange` / Queue: `history.events`

## Rate Limiting 방식

- **GitHub**: 평상시 300ms 고정 딜레이, `X-RateLimit-Remaining` ≤ 10이면 `X-RateLimit-Reset` 시각까지 동적 대기
- **Slack**: 엔드포인트별 고정 딜레이 (conversations.list 3,000ms / history·replies 1,200ms)
- **Jira**: 호출당 200ms 고정 딜레이

## Checkpoint

- 재시작 시 중복 수집 방지 — 마지막 수집 시각 이후 데이터만 수집
- 체크포인트 기준: `Instant.now()`가 아닌 **이벤트 실제 발생 시각**(`occurredAt`) 기준으로 갱신
- GitHub: 타입별 독립 체크포인트 (`commitsScannedAt` / `pullRequestsScannedAt` / `issuesScannedAt`)
  - occurredAt 기준: Commit → `committed_at`, PR → `merged_at`, Issue → `updated_at`
- Slack: `lastScannedAt` 단일 체크포인트. 루트 메시지가 checkpoint 이전이라도 `latest_reply`가 이후면 스레드 reply 수집
- Jira: `lastScannedAt` 단일 체크포인트. `created`가 아닌 `updated` 기준으로 필터링. 페이지 단위 publish + checkpoint 갱신 (`app.jira.max-pages-per-run`으로 상한 설정)
- 파일 저장: `.tmp` 원자적 쓰기 (`Files.move(ATOMIC_MOVE)`)

> 수집 전략 상세(페이지네이션·Rate Limiting·Reply 전략 등)는 `docs/data-collection.md` 참고

## 설정 파일

`src/main/resources/application.yaml` — RabbitMQ 연결, GitHub/Jira/Slack base URL, rate limit 임계값, checkpoint 파일 경로, `app.jira.max-pages-per-run` (기본값 50)

## 규칙 및 주의사항

- 새 데이터 소스 추가 시 `*RawService` + `*Normalizer` + `*RateLimiter` 3개 세트로 구현
- 머지 커밋은 GitHub 정규화 시 필터링 — 변경 금지
- GitHub PR 수집은 `/pulls?state=closed` + 클라이언트 `merged_at != null` 필터 방식. Search API(`/search/issues`)는 사용하지 않음
- `/api/v1/raw/*` 엔드포인트는 디버그용으로 유지 (제거 금지)
