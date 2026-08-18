# pipeline-worker CLAUDE.md

## 실행 명령어

```bash
cd services/pipeline-worker
./gradlew bootRun
./gradlew test
./gradlew test --tests "패키지.클래스명"
./gradlew build
```

전체 스택(postgres·rabbitmq 등 의존 포함)은 `infra/docker`의 docker-compose로 기동한다. 자세한 절차는 루트 `CLAUDE.md`를 참고한다.

## 패키지 구조

| 패키지 | 역할 |
|--------|------|
| `controller` | HTTP endpoint. 비즈니스 로직을 넣지 않는다. |
| `pipeline` | 수집 오케스트레이션. 어떤 provider를 어떤 순서로 돌릴지만 정하고, 실제 수집은 `SourceCollector`에 위임한다. |
| `collection` | `SourceCollector` SPI와 레지스트리, 프로젝트별 수집 설정·webhook payload 해석 경계. DB의 project/integration 정보를 조회한다. |
| `trigger` | backend 연동 완료 요청을 provider별 비동기 초기 수집으로 연결한다. |
| `webhook` | GitHub webhook 검증, 필터링, delivery 중복 처리, 비동기 수집 트리거. |
| `source.github` | GitHub 자격증명 해석·수집·정규화·rate limit (`GitHubCollector`). |
| `source.jira` | Jira 자격증명 해석·수집·정규화·rate limit (`JiraCollector`). |
| `source.linear` | Linear 자격증명 해석·수집·정규화·rate limit (`LinearCollector`). |
| `source.asana` | Asana 자격증명 해석·수집·정규화·rate limit (`AsanaCollector`). |
| `source.clickup` | ClickUp 자격증명 해석·수집·정규화·rate limit (`ClickUpCollector`). |
| `source.slack` | Slack 자격증명 해석·수집·정규화·rate limit (`SlackCollector`). |
| `source.discord` | Discord 수집·정규화·rate limit (`DiscordCollector`). 자격증명은 DB가 아니라 이 worker의 설정(`app.discord.bot-token`)에서 온다 — 수집 주체가 앱 전체 공유 봇이라서다. |
| `source.googlechat` | Google Chat 수집·정규화·rate limit (`GoogleChatCollector`). Jira와 같은 모양 — DB의 사용자별 JSON credential(`access_token`)을 복호화해 Bearer로 쓰고, 만료 시 backend(`GoogleChatTokenService`)가 갱신한다. 사용자 인증으로는 메시지 작성자 표시 이름이 Chat API 응답에 오지 않아(실측 확인) `GoogleChatRawService`가 People API(`people.googleapis.com`, 별도 호스트)로 이름·이메일을 보강한다 — 메시지에 등장한 sender만 TTL 캐시로 지연 조회(`app.google-chat.person-cache-ttl`). |
| `source.notion` | Notion 수집·정규화·rate limit (`NotionCollector`, **문서 아키타입 1호** — `Document` nodeType 발행). ClickUp과 같은 모양 — DB의 JSON credential(`access_token`)을 복호화해 Bearer로 쓰고 만료 판정을 하지 않는다(갱신 응답에 만료 정보가 없어 비만료 취급). `POST /v1/search`(최신 API 버전은 `Notion-Version` 헤더로 고정)를 `last_edited_time` 내림차순으로 훑고, 페이지마다 `GET /v1/blocks/{id}/children`을 재귀 조회해 `NotionBlockFlattener`로 평문화한다(깊이 5·블록 2,000·본문 100,000자 상한). `created_by`/`last_edited_by`는 partial user(id만)라 `GET /v1/users` 전량 조회 결과(TTL 캐시, `app.notion.user-cache-ttl`)로 이름·이메일·bot 여부를 보강한다 — capability 미설정으로 인한 403은 삼키고 빈 맵으로 계속한다. |
| `normalizer` | 여러 source가 공유하는 정규화 보조 유틸. 현재 `RefsExtractor` 유지 — URL 기반 참조 레지스트리(`issueExternalRefs`/`documentExternalRefs`)로 Asana·ClickUp·Notion을 함께 다룬다. |
| `checkpoint` | DB `checkpoints` 테이블 기반 커서 조회/갱신 경계 + 배치의 커서 전진 값 계산(`CursorProgress`). |
| `messaging` | RabbitMQ publish. |
| `common` | 전역 공유 코드. 현재 `common.crypto`(integration credential 복호화)만 있다. |
| `config` | Spring/RabbitMQ/WebClient/webhook executor 설정. |
| `dto` | 요청/응답/event DTO. |
| `util` | 일반 유틸. |

## SourceCollector SPI — 새 소스 추가 지점

provider별 수집은 `collection.SourceCollector` 구현체가 전부 소유한다. 오케스트레이션 계층
(`pipeline`, `trigger`, `collection`)은 provider를 알지 못하고 `SourceCollectorRegistry`로만 구현체를 찾는다.

```java
CollectionProvider provider();
Optional<RawFetchRequest> resolveFetchRequest(IntegrationRow integration);  // 자격증명 복호화 + external_ref 해석
int collect(String projectId, RawFetchRequest request);                     // fetch → normalize → publish → checkpoint
```

`resolveFetchRequest`의 실패 신호는 두 가지로 구분한다 — 호출부가 다르게 처리하기 때문이다.

- `Optional.empty()` — 연동은 정상이나 지금 수집 불가(예: 만료된 GitHub installation token).
- `IllegalStateException`/`IllegalArgumentException` — 연동 설정이 깨짐(예: 필수 external_ref 누락).

이 예외를 삼킬지는 provider가 아니라 **호출 맥락**이 정한다(`ProjectIntegrationService`):
webhook context 조립에서 **GitHub만 전파**한다(앵커라 조용히 넘기면 "연동 없음"으로 오인돼 수집이
멈춘 걸 아무도 모른다). 나머지 provider와 초기 수집 트리거 경로는 삼키고 그 provider만 건너뛴다.

**새 소스를 추가할 때 편집하는 곳**은 다음뿐이다.

1. `CollectionProvider`에 상수 추가
2. `source/{provider}` 패키지에 `SourceCollector` 구현 `@Service` 추가

routing key는 설정하지 않는다 — `EventPublisher`가 `source`에서 유도한다
(`{app.rabbitmq.routing-key-prefix}` + `.` + 소문자 source, 예: `GOOGLE_CHAT` → `event.google_chat`).
큐 바인딩이 `event.#`라 브로커 설정도 불변이다.

`EventPublisher`·`PipelineService`·`CollectionTriggerService`·`ProjectIntegrationService`·`CheckpointService`는
건드리지 않는다.
발행 계약(nodeType별 properties·refs·source 표기)은 `docs/normalized-event.md`가 단일 출처이며,
backend·프론트까지 포함한 커넥터 전체 순서는 `docs/integration-abstraction.md`의
「커넥터 엔드투엔드 체크리스트」에 있다.

## Endpoint

진입점별 상세 동작은 아래 흐름 다이어그램을 참고한다.

- `POST /api/v1/webhook/github` — GitHub webhook 수신. `pull_request` + `action=closed` + `merged=true`만 수집 트리거.
- `POST /api/v1/collect/{provider}` — backend 연동 완료 후 해당 단일 provider의 초기 수집을 비동기로 요청(`202` 반환, 완료를 기다리지 않음). `{provider}`는 `CollectionProvider`가 아는 값이면 전부 받는다(github/jira/slack/discord/google-chat/linear/asana/clickup/notion).
- `POST /api/v1/raw/{provider}` — 디버그용 raw 샘플(1페이지). DB checkpoint를 사용하지 않으며 전체 수집 용도가 아니다.

## 초기 수집 트리거 흐름

```text
backend integration connect commit
  -> POST /api/v1/collect/{provider} {projectId}
  -> ProjectIntegrationService.resolveFetchRequest(projectId, provider)
     (해당 provider의 SourceCollector가 credential 복호화·external_ref 해석)
  -> collectionTaskExecutor에서 비동기 실행 (webhook 풀과 분리된 초기 수집 전용 풀)
  -> PipelineService.collect(projectId, provider, request) -> SourceCollector.collect(...)
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
  -> context에 담긴 provider(GitHub 제외) 각각에 대해 backend 내부 API로 토큰 확보 요청
     -> REFRESHED: 그 provider 부분만 재해석해 context 교체
     -> NOT_SUPPORTED(501, 갱신 수단 없음 — Slack·Discord·ClickUp): 저장된 자격증명 그대로 context 유지
     -> FAILED(404 연동 행 없음 · 그 밖의 오류): 그 provider만 context에서 제외
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
GitHub(앵커) 외 나머지 provider 연동은 전부 선택 항목이므로 credential 또는 external_ref가 잘못된 경우 해당 provider를 건너뛰고 가능한 provider 수집은 진행한다.
만료 토큰형 provider(Jira·Google Chat·Linear·Asana)는 `IntegrationTokenClient.ensure(projectId, provider)`로 토큰 확보를 시도한다 — 갱신 성공(`204` → REFRESHED)이면 그 provider만 재해석, 갱신 수단이 없는 provider(Slack·Discord·ClickUp, `501` → NOT_SUPPORTED)는 저장된 자격증명 그대로 진행, 확보 실패(FAILED — **연동 행 없음 `404`**·backend 오류·네트워크 예외가 이 하나로 흡수된다)면 그 provider만 건너뛰고 나머지 수집은 계속한다. **`404`를 NOT_SUPPORTED로 읽으면 안 된다** — 해제 직후 레이스가 "갱신 불필요"로 읽혀 폐기된 토큰으로 수집을 진행하게 된다(backend가 능력 없음은 `501`, 리소스 없음은 `404`로 갈라 답하는 이유다). `IntegrationTokenClient`는 원래 Jira 전용(`JiraTokenClient.ensureJiraToken`)이었으나 Google Chat 추가를 계기로 provider를 인자로 받는 형태로 일반화했다(선행 PR).
`webhookTaskExecutor`가 작업을 받을 수 없으면 `IN_PROGRESS` claim을 해제해 GitHub 재시도가 다시 claim할 수 있게 한다.
애플리케이션 시작 시 `app.webhook.delivery.stale-in-progress-timeout`보다 오래된 `IN_PROGRESS` delivery는 `FAILED`로 정리한다.
`webhookTaskExecutor`는 `app.webhook.executor.pool-size`(기본 4)로 여러 프로젝트 webhook을 병렬 처리한다. 단 동일 프로젝트의 동시 수집은 `ProjectCollectionSerializer`(project id striped lock)가 직렬화해 같은 구간 중복 풀스캔을 막는다. 초기 수집(`collectionTaskExecutor`)은 provider별 병렬이 의도라 직렬화 대상이 아니다.

## RabbitMQ 라우팅

| Source | Routing key |
|--------|-------------|
| GitHub | `event.github` |
| Jira | `event.jira` |
| Slack | `event.slack` |
| Discord | `event.discord` |
| Google Chat | `event.google_chat` |
| Linear | `event.linear` |
| Asana | `event.asana` |
| ClickUp | `event.clickup` |
| Notion | `event.notion` |

Exchange: `history.exchange` / Queue: `history.events` (바인딩 `event.#` — 새 routing key는 브로커 설정 변경 없이 소비된다)

발행은 publisher confirm으로 검증한다. `EventPublisher`가 메시지를 일괄 전송한 뒤 `CorrelationData`별 broker ack/return을 모아 대기하고, 한 건이라도 nack·라우팅 실패(unroutable)·타임아웃이면 `EventPublishException`을 던진다. 그러면 해당 `SourceCollector`가 checkpoint를 전진시키지 않아(예외로 갱신 호출이 스킵됨) 유실(영구 빈칸)을 막고 다음 수집에서 재발행한다. 이를 위해 연결은 `publisher-confirm-type: correlated` + `publisher-returns: true`, 템플릿은 `mandatory=true`로 설정한다. confirm 대기 한도는 `app.rabbitmq.publish-confirm-timeout-ms`.

## Rate Limiting

- **GitHub**: 3단 적응형. `X-RateLimit-Remaining`이 500(`pacing-remaining-threshold`) 초과면 무대기, 10(`low-remaining-threshold`) 초과 500 이하면 `X-RateLimit-Reset`까지 남은 시간을 remaining으로 나눈 페이스((reset−now)/remaining)로 대기, 10 이하면 `X-RateLimit-Reset`까지 대기. 헤더가 없거나 remaining/reset을 숫자로 파싱할 수 없으면 300ms(`default-delay-ms`)로 폴백.
- **Slack**: endpoint별 고정 딜레이 (`conversations.list` / `history` / `replies`). 429 응답은
  `Retry-After` **헤더**(정수 초, 없거나 형식이 어긋나면 60초 폴백)만큼 대기 후 최대 3회 재시도하고,
  첫 429부터는 그 실행 동안 해당 endpoint의 호출 간격을 Retry-After 값으로 승격한다(`SlackPacing` —
  실행 단위 상태라 싱글턴 rate limiter와 달리 동시 수집 중인 프로젝트 간에 섞이지 않는다). 배경:
  2025-05-29 이후 생성된 비마켓플레이스 배포형 앱은 `history`/`replies`가 분당 1회·요청당 15건으로
  제한된다(Marketplace 승인 시 해제). 고정 딜레이는 구 한도 기준을 유지하고 신규 한도는 429 적응으로
  흡수하므로 어느 체제든 설정 변경이 필요 없다. `ok:false`/null 응답은 빈 페이지로 삼키지 않고 즉시
  예외로 실패시킨다(조용히 넘기면 "채널 완주"로 위장돼 채널 커서를 잘못 전진시킨다).
- **Jira**: 호출당 200ms 고정 딜레이.
- **Discord**: 호출마다 기본 250ms 고정 딜레이(봇당 초당 50요청 상한에 여유). 429 응답은 본문의
  `retry_after`(초)만큼 대기 후 최대 3회 재시도한다.
- **Google Chat**: 호출마다 기본 100ms 고정 딜레이(Cloud 프로젝트당 60초 3,000요청 쿼터 — 사용자 수와
  무관하게 앱 전체가 공유). 429 응답에는 Discord처럼 재시도 대기 시간을 알려주는 필드가 없어 지수
  백오프(`min((2^n)+jitter, 30s)`)로 최대 5회 재시도한다.
- **Linear**: 호출당 720ms 고정 딜레이 (API 한도 5,000 req/h 기준).
- **Asana**: 호출당 400ms 고정 딜레이 (무료 워크스페이스 한도 150 req/min 기준).
- **ClickUp**: 호출당 600ms 고정 딜레이 (무료 워크스페이스 한도 100 req/min 기준).
- **Notion**: 호출마다 기본 350ms 고정 딜레이(연결당 평균 3 req/s 기준). 429·529 응답은 Google Chat과
  달리 서버가 `Retry-After` 헤더(초)로 대기 시간을 알려주므로 있으면 그대로 따르고, 없을 때만 지수
  백오프(`min((2^n)+jitter, 30s)`)로 최대 5회 재시도한다.

Slack `users.list`는 webhook 수집마다 전체 멤버를 재조회하면 비싸므로(Tier 2, 페이지당 3s), auth별로 `app.slack.user-map-cache-ttl`(기본 5m) 동안 캐시해 실행 간 재사용한다. 트레이드오프: TTL 윈도우 안에 가입한 신규 멤버의 메시지는 그 동안 `userName`/`userEmail` 보강 없이 수집될 수 있으며, ai-engine의 Actor 보정이 backstop이다. 정합성을 더 조이려면 TTL을 줄이거나(0=비활성) miss-refresh로 발전시킨다.

## Checkpoint

- cursor_key는 **provider가 소유한다**. `CheckpointService`는 `(project, provider, cursor_key)` 키-값 저장소일 뿐
  키를 해석하지 않으므로, provider마다 커서를 몇 개 쓰든(GitHub 3개, Jira·Slack·Discord·Google Chat 1개) 저장소는 그대로다.
- 현재는 DB `checkpoints` 테이블을 사용한다.
- 재시작 시 마지막 수집 시각 이후 데이터만 수집해 누락을 방지하고 중복을 최소화한다.
- checkpoint 기준은 `Instant.now()`가 아니라 이벤트 실제 발생 시각인 `occurredAt`이다.
- GitHub는 타입별 checkpoint를 사용한다: `github/github_commits`, `github/github_pull_requests`, `github/github_issues`.
- Jira는 `jira/jira_updated`, Slack은 **채널별** `slack/slack_messages:<channelId>`(레거시 전역
  `slack_messages`는 읽기 fallback 후 이관 완료 시 삭제), Discord는 `discord/discord_messages`,
  Google Chat은 `google-chat/google_chat_messages`, Linear는 `linear/linear_updated`, Asana는
  `asana/asana_updated`, ClickUp은 `clickup/clickup_updated`, Notion은 `notion/notion_pages`
  cursor를 사용한다.
- GitHub PR checkpoint는 commit 처리 성공 후 갱신해 재시작 시 `sha → prNumber` 매핑을 다시 만들 수 있게 한다.
- Discord·Linear·Asana·ClickUp·Notion은 전체 실행 중 최대 `occurredAt`을 마지막에 한 번(성공 시에만)
  갱신한다. Discord는 채널을 가로지르는 커서가 하나라 채널별로 갱신하면 늦은 채널이 이른 채널의 커서를
  덮기 때문이고, Linear·Notion은 정렬이 내림차순 고정(Linear `orderBy: updatedAt`, Notion
  `POST /v1/search`의 `last_edited_time desc` — 이쪽은 애초에 오름차순 옵션 자체가 없다)이라 페이지
  단위로 전진시키면 truncation 시 과거 변경분이 영구 누락되기 때문이며, Asana·ClickUp은 API 응답에
  정렬 보장이 없어 페이지 단위 전진 자체가 불가능하기 때문이다. Google Chat은 스페이스가 하나뿐이라
  이 문제 자체가 없지만, 배치의 최대 `occurredAt`으로 갱신하는 공용 규약은 그대로 따른다.
- Slack은 **채널별 커서**를 쓴다: 채널의 history를 끝까지 걸은(완주한) 직후 그 채널 키를 갱신한다 —
  키가 채널별이라 덮어쓰기 문제가 없고, 중간에 죽어도 완주한 채널의 진행이 보존돼 다음 실행이 그
  채널을 건너뛴다. history가 최신→과거 순이라 최대 `occurredAt`이 1페이지에 오므로 **페이지 단위
  전진은 불가**하고 채널 완주가 안전한 최소 단위다. 채널 목록에 없는 채널의 고아 키는 목록 조회 직후
  `CheckpointService.deleteCursor`로 정리한다. history 요청에는 `oldest = 채널커서 − 14일`
  (`THREAD_LOOKBACK`)을 붙인다 — 상세와 알려진 한계는 `docs/data-collection.md` Slack 절 참고.
  부수 효과: backend "마지막 수집 시간"(checkpoints의 최신 `updated_at` 파생)이 채널 완주마다 전진한다.
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
- GitHub/Jira/Slack/Discord/Google Chat/Notion base URL (Jira는 `app.jira.gateway-base-url`도 포함 — OAuth
  cloudId 게이트웨이 주소, Google Chat·Notion은 각각 `app.google-chat.api-base-url`·
  `app.notion.api-base-url` 하나뿐 — 앱 수준 자격증명은 backend만 쓴다)
- Discord 봇 토큰 (`app.discord.bot-token`, 환경변수 `DISCORD_BOT_TOKEN`)
- Notion API 버전 헤더 고정값 (`app.notion.version`) · 사용자 전량 캐시 TTL (`app.notion.user-cache-ttl`)
- rate limit 값
- GitHub webhook secret
- webhook executor 종료 대기 시간
- 초기 수집 executor 풀 크기·큐·종료 대기 시간 (`app.collection.executor.*`)
- stale `IN_PROGRESS` webhook delivery 정리 기준 시간

사용자/프로젝트별 credential은 `application.yaml`에 두지 않는다. DB의 project integration 정보에서 조회하고 `security.credentials.key`로 복호화한다.
GitHub/Slack/Jira/Google Chat credential은 모두 Bearer 토큰으로 사용한다. Jira·Google Chat은 OAuth
전환 후 DB에 JSON(`access_token`·`refresh_token`·`expires_at`)으로 저장되며, 각 `*Collector`가
복호화해 `access_token`만 꺼내 Bearer로 감싼다. 사용자가 토큰을 직접 입력하는 경로가 없으므로
`JiraRawService.resolveAuth`는 **Bearer 외 포맷을 거부한다** — 과거 `email:token`(Basic) 지원은 제거됐다.

**Discord는 이 패턴의 예외다** — DB row의 `encrypted_credential`(사용자 OAuth refresh token)을 전혀
복호화하지 않는다. 수집 주체가 프로젝트별 사용자가 아니라 앱 전체가 공유하는 봇이라, `DiscordCollector`는
`resolveFetchRequest`에서 `app.discord.bot-token`을 `Bot {token}`으로 감싸 쓰고, DB에서는 `external_ref.guild_id`만 읽는다.
Google Chat은 반대로 Jira와 완전히 같은 모양이다(비교하려면 `source/jira`가 가장 가까운 참고 코드다) —
사용자별 access token이 DB에 있고 만료되면 backend가 갱신한다.
Notion은 ClickUp과 같은 모양이다 — DB JSON credential(`access_token`·`refresh_token`)을 복호화해
`access_token`만 Bearer로 쓰고 만료 판정을 하지 않는다. `refresh_token`은 갱신에 쓰이지 않지만
(backend에 `AccessTokenRefresher` 미구현) JSON에는 함께 저장돼 있다 — `NotionCollector`는 읽지 않는다.

로컬 실행과 배포 시 다음 환경변수를 설정한다.

- `BACKEND_CREDENTIAL_KEY`: backend와 동일한 credential 암호화 키. 필수.
- `INTERNAL_SERVICE_TOKEN`: backend와 동일한 내부 서비스 공유 token. 필수.
- `BACKEND_URL`: backend 내부 API 주소. 기본값은 `http://localhost:8080`이며 배포 환경에서는 명시한다.

GitHub App private key는 pipeline-worker에 설정하지 않는다. token 발급은 backend가 전담한다.

## 규칙 및 주의사항

- Controller에는 수집/정규화/publish/checkpoint 조합 로직을 넣지 않는다.
- **오케스트레이션 계층에 provider 분기(switch·if)를 만들지 않는다.** provider별 동작은 `SourceCollector`
  구현으로 표현한다 — 분기를 한 번 허용하면 소스가 늘 때마다 같은 자리를 계속 고쳐야 한다.
- provider별 API 호출/정규화/rate limit/자격증명 해석은 `source.{provider}` 패키지 안에서 처리한다.
- `SourceCollector.collect`는 발행 예외를 삼키지 않는다 — 예외가 나야 checkpoint가 전진하지 않아
  다음 수집에서 재발행된다. 삼키면 그 구간이 영구 누락된다.
- GitHub merge commit은 `GitHubRawService`가 목록 응답의 parents 개수로 상세 조회 전에 사전 스킵하고, `GitHubNormalizer`가 이중 방어로 필터링한다.
- GitHub PR 수집은 `/pulls?state=closed` + 클라이언트 `merged_at != null` 필터 방식이다.
- GitHub 수집은 integration에 브랜치가 지정되면 해당 단일 브랜치로 스코프한다: PR은 `base={branch}`(타겟 브랜치 기준), commit은 `sha={branch}` 파라미터로 제한한다. 브랜치 미지정이면 전체 브랜치를 수집한다.
- `/api/v1/raw/*` endpoint는 디버그용 샘플이다. 전체 수집 용도로 사용하지 않는다.
