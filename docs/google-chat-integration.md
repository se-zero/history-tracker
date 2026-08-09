# Google Chat 연동 계획 — 대화 아키타입

`docs/integration-abstraction.md` Part B의 대화 아키타입 커넥터다. Slack이 레퍼런스 구현이며,
ai-engine은 무변경이다(`Communication` 노드 재사용). 전체 순서는 「커넥터 엔드투엔드 체크리스트」를
따르고, 이 문서는 그 체크리스트를 Google Chat에 대입했을 때의 **결정 사항과 Google Chat 고유 설계**만
다룬다.

Google Workspace Chat API 공식 문서 조사(2026-08, v1 기준)를 근거로 작성했다. **아직 실측은 하지
않았다** — Discord 문서의 「확인 완료」에 해당하는 절이 여기엔 없고, 대신 착수 직전에 반드시 재보는
항목을 「착수 전 실측」에 모았다. 특히 §1-0의 계정 게이트는 **코드를 한 줄도 쓰기 전에** 확인해야 한다.

**순서**: 문서상 3호지만 Teams가 라이선스 확보를 기다리는 중이라 실제 착수는 2호가 될 수 있다.
Discord와 Teams 중 어느 쪽이 앞서든 이 커넥터가 맡는 검증 항목은 그대로다(아래).

## 이 커넥터가 검증하는 것

1. **대화형에서 A4 다단 선택 메커니즘이 통하는가** — Discord가 남긴 숙제다. Discord는 자기 동의
   화면에서 서버를 고르게 해 선택 단계가 아예 없었고(Slack형), Teams는 착수가 밀렸다. Google Chat은
   `spaces.list`로 **1단(space) 선택**이 필요하므로 A4 메커니즘을 대화 아키타입에서 처음 태운다.
2. **만료 토큰 provider의 웹훅 경로** — Teams 문서 §2에 적힌 선행 공용 변경(webhook 토큰 확보
   일반화)이 여기서 **필수가 된다**(§2). Jira 외의 두 번째 만료 토큰 provider라, 그 일반화 없이는
   증분 수집이 만료된 access token으로 401을 맞는다.
3. **서버사이드 시간 필터 증분** — `filter=createTime > {checkpoint}`가 checkpoint 저장소의 `Instant`
   계약과 정확히 맞물린다. Slack의 히스토리 풀스캔도, Discord의 `before` 역방향 보정도 필요 없는
   가장 단순한 형태라, 대화 아키타입 증분 전략 3종(풀스캔 / snowflake 역방향 / 시간 필터)의 마지막
   조각이 채워진다.

## 0. 결정 사항 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| provider 표기 | RDB/경로 `google-chat` · source `GOOGLE_CHAT` · alias `GOOGLE_CHAT:{userId}` · routing `event.google_chat`(자동 유도) | `docs/normalized-event.md`「source·표기 규칙」의 두 단어 예시가 이미 이 형태다. `integration-abstraction.md` §5-2의 미결 항목을 이것으로 확정한다(§10) |
| API | Google Chat REST **v1** (`https://chat.googleapis.com/v1`) | |
| 인증 모델 | **사용자 OAuth(3LO)** — 앱 인증(서비스 계정) 아님 | 앱 인증으로 메시지를 읽으려면 `chat.app.messages.readonly` + **Workspace 관리자 승인**이 필요하고 공개 메시지만 반환된다. 사용자 인증은 그 사용자가 볼 수 있는 것을 그대로 읽는다 |
| 연결 플로우 | OAuth → **1단 선택(space)** → 확정 | `spaces.list`(`spaceType = "SPACE"`)로 후보 조회. A4 메커니즘 재사용 |
| 수집 범위 | 선택한 **스페이스 1개**의 전체 메시지(스레드 답글 포함). DM·그룹 채팅 제외 | §3의 트레이드오프 참고 |
| 토큰 | 만료+갱신형 — `AccessTokenRefresher` **구현** | access token ~1시간. refresh token은 **회전하지 않는다**(갱신 응답에 `refresh_token`이 없다) — Atlassian·Microsoft와 반대라 갱신 시 기존 값을 유지해야 한다 |
| 원격 폐기 | `ProviderCredentialLifecycle` **구현** | Google은 `POST https://oauth2.googleapis.com/revoke`로 grant를 폐기할 수 있다(Teams와 달리 수단이 있다) |
| 증분 | `filter=createTime > "{RFC-3339}"` + `orderBy=createTime ASC` | 서버사이드 필터라 checkpoint `Instant`를 그대로 넣는다. 클라이언트 경계 필터링 불필요 |
| 선행 공용 변경 | ~~① `checkpoints` provider CHECK 제거(A9)~~ ✅ 완료 · ② webhook 토큰 확보 일반화 — **필수 선행** | ②는 Jira 하드코딩(`ensureFreshJiraToken`) 때문에 만료 토큰형 두 번째 provider가 증분에서 401을 맞는다 (§2) |
| **진입 장벽** | **Google Workspace 계정 필요**(개인 gmail.com 불가) — 단 무료 경로가 있을 수 있다 | Chat API는 Workspace 조직에 속한 계정만 구성할 수 있다. 비용은 Teams보다 낮지만 0은 아니다 (§1-0) |
| 개인정보 | `actor.email`을 **기본적으로 못 얻는다** | Chat API `User` 리소스에 email 필드가 없다. Discord와 같은 처지 (§7) |

## 1. 사전 준비

### 1-0. 선행 조건 — Google Workspace 계정 게이트 (**착수 전 실측 필수**)

Teams §1-0과 같은 성격의 관문이 여기에도 있다. 다만 **정도가 다르다**.

- Google 문서의 오류 메시지가 명시적이다 — *"Google Chat API is only available to Google Workspace
  users"* 는 **Chat API를 구성하는 데 쓴 계정이 Workspace 조직에 속하지 않을 때** 뜬다.
  개인 gmail.com 계정으로는 Chat 앱을 만들거나 Chat API를 쓸 수 없다.
- 반대로 **최종 사용자 쪽 제약은 Teams보다 가볍다.** Teams는 유료 라이선스가 개발자·사용자 양쪽에
  필요했지만, Google Chat은 (조사 범위에서는) API를 **구성하는** 쪽이 Workspace여야 한다는 요건이
  주된 제약이다. 관리자 동의는 `chat.app.*`(앱 인증) 계열에 걸리는데, 우리는 사용자 인증을 쓰므로
  기본 경로에서는 필요하지 않다.

착수 가능한 계정 경로는 셋이다. **어느 것이든 먼저 30분 실측으로 확정하고 시작한다.**

| 경로 | 비용 | 확인할 것 |
|------|------|----------|
| **대학 Workspace 계정**(캡스톤 팀이 가진 학교 계정) | 무료 | 학교가 Chat을 켜 뒀는지, 외부 Cloud 프로젝트에서 OAuth 앱을 만들 수 있는지(조직 정책으로 막는 경우가 있다) |
| **Workspace Essentials Starter** | 무료(문서상). 단 **gmail.com이 아닌 자체 도메인 이메일**로 가입해야 하고 Gmail은 포함되지 않는다 | 이 에디션에서 Chat **API**가 열리는지. 저가/무료 에디션은 API가 빠지는 경우가 있어 여기가 핵심 미확인점이다 |
| **Business Starter 체험** | 14일 무료, 이후 유료 | 체험 기간이 끝나면 연동이 죽으므로 검증용으로만 |

**실측 절차(코드 착수 전).**

1. 후보 계정으로 Google Cloud 프로젝트를 만들고 **Chat API를 사용 설정**한다.
   여기서 *"only available to Google Workspace users"* 가 뜨면 그 계정은 탈락이다.
2. OAuth 클라이언트를 만들고 [OAuth Playground](https://developers.google.com/oauthplayground)나
   임시 스크립트로 `chat.spaces.readonly` 동의를 받아 `GET /v1/spaces?filter=spaceType = "SPACE"`를
   호출한다. **스페이스 목록이 오면 §3의 선택 단계가 성립한다.**
3. 그 중 하나로 `GET /v1/spaces/{id}/messages?filter=createTime > "..."` 를 호출해
   본문(`text`)·`sender`·`thread`가 실제로 채워져 오는지 본다. Discord의 MESSAGE_CONTENT처럼
   **조용히 비는 필드가 없는지**가 확인 목적이다.

1·2단계에서 막히면 그 시점에 계정 경로를 갈아타는 게 전부다 — 코드는 아직 없다.

### 1-1. Google Cloud 프로젝트·OAuth 앱 등록

- Google Cloud 프로젝트에서 **Google Chat API 사용 설정** 후 Chat 앱 구성(이름·아바타·설명)을
  채운다. 사용자 인증만 쓰더라도 Chat API 구성 페이지를 요구하는지는 「착수 전 실측」 2번이다.
- **OAuth 동의 화면의 User type이 운영 난이도를 가른다.**

  | User type | 검증 | refresh token 수명 | 쓸 곳 |
  |-----------|------|-------------------|-------|
  | **Internal**(같은 Workspace 조직 전용) | 불필요 — 민감·제한 scope도 검토 없이 쓴다 | 정상(무기한, 6개월 미사용 시 만료) | 팀 내부 개발·시연 |
  | **External + Testing** | 불필요(테스트 사용자 100명 한도) | **7일** — 만료되면 재동의해야 한다 | 외부 계정 테스트 |
  | **External + 게시** | 민감 scope 검증 필요(수일) | 정상 | 실제 제품화 |

  **개발 중에는 Internal을 쓴다.** External + Testing으로 개발하면 **refresh token이 7일마다 죽어**
  "어제까지 되던 연동이 오늘 갑자기 재동의를 요구하는" 현상을 겪는데, 이는 우리 코드 버그가 아니라
  게시 상태 때문이다. 이걸 모르면 `AccessTokenRefresher`를 며칠 붙잡고 디버깅하게 된다.
- redirect URI: `{BASE}/api/v1/integrations/google-chat/callback` — **소문자 kebab이며 이후 변경
  불가**(등록된 URI가 깨진다). `{BASE}`는 **프론트 오리진**이다. 콜백이 돌려주는 302가 상대 경로라
  backend(:8080)를 직접 등록하면 연동은 성공해도 마지막 리다이렉트가 401로 끝난다
  (Discord에서 실제로 겪은 사고 — `docs/discord-integration.md` §1 참고).
  Google은 web 클라이언트에 https를 요구하되 `http://localhost`는 예외로 허용하므로,
  로컬은 `http://localhost:5173/...`, 터널을 쓰면 터널 도메인이다.
- **scope (최소 권한)**

  | scope | 용도 | 비고 |
  |-------|------|------|
  | `.../auth/chat.spaces.readonly` | `spaces.list` — 선택 단계 후보, `spaces.get`(스페이스 이름) | |
  | `.../auth/chat.messages.readonly` | `spaces.messages.list` — 메시지·스레드 답글 | |
  | `.../auth/directory.readonly` | (선택·2차) People API로 `actor.email` 보강 | §7. 1차 범위에서는 **요청하지 않는다** |

  동의 URL에 **`access_type=offline`과 `prompt=consent`를 반드시 넣는다** — 없으면 refresh token이
  발급되지 않아(또는 두 번째 동의부터 빠져) 갱신 자체가 불가능해진다.
- 환경변수(`ATLASSIAN_*` 패턴): `GOOGLE_CHAT_CLIENT_ID` · `GOOGLE_CHAT_CLIENT_SECRET` ·
  `GOOGLE_CHAT_REDIRECT_URI`. backend에만 필요하다(Discord와 달리 pipeline-worker는 DB의 사용자
  토큰으로 수집한다). `infra/docker/docker-compose.yml` backend 블록에 추가하고 실제 값은 `.env`.

## 2. 선행 공용 변경 (커넥터 PR과 분리) — 두 가지

### ~~2-0. `checkpoints.provider` 열거형 CHECK 제거~~ (A9, ✅ 완료 — 2026-08-09)

Google Chat 고유 항목은 아니었지만 착수 전 반드시 처리해야 했던 공용 blocker다. V5가 만든
`chk_checkpoints_provider CHECK (provider IN ('github','jira','slack'))`가 남아 있어 새 provider의
checkpoint 쓰기가 제약 위반으로 터지는 문제였다(2026-08-09 Discord 실기동에서 발견 — 발행은 끝난
뒤라 그래프에는 데이터가 들어가고 커서만 전진하지 못해 매 수집이 같은 구간을 다시 긁는 증상이었다).
`V13__drop_checkpoints_provider_constraint.sql`로 제거하고 Discord 재수집으로 정상 동작을 실측
확인했다. 상세는 `docs/integration-abstraction.md`의 **A9**.
**Google Chat 커넥터 착수 시점에는 이미 해결된 상태이므로 별도 조치 불필요.**

### 2-1. webhook 토큰 확보 일반화 (만료 토큰형 provider)

`docs/teams-integration.md` §2에 설계된 그 작업이며, **Google Chat에서는 선택이 아니라 전제다.**
현재 코드는 `GitHubWebhookService.ensureFreshJiraToken` + `JiraTokenClient`로 Jira만 특별 취급한다
(확인함 — 2026-08-09 기준 그대로다). 그대로 두면 Google Chat 연동은 초기 수집은 되지만 **PR 머지
웹훅으로 도는 증분에서 만료된 access token을 그대로 써 401**을 맞는다.

backend 내부 API는 이미 범용(`/api/v1/internal/integrations/{projectId}/{provider}/token`)이므로
호출부만 일반화한다.

- `JiraTokenClient` → `IntegrationTokenClient.ensure(projectId, provider)`로 개명·범용화.
  결과는 3값: **REFRESHED**(204) / **NOT_SUPPORTED**(404) / **FAILED**(그 외·예외).
- webhook context 조립 시 앵커(GitHub)를 제외한 context 내 모든 provider에 ensure를 호출한다.
  - REFRESHED → 해당 provider의 fetch request 재해석(현행 Jira 동작).
  - NOT_SUPPORTED → **저장된 자격증명 그대로 진행.** Slack·Discord가 여기 해당한다.
    404를 '수집 제외'로 해석하면 Slack·Discord가 끊긴다 — 조용한 204 사건의 거울상이니 테스트로 고정.
  - FAILED → 해당 provider만 제외하고 진행(현행 Jira 동작과 동일).
- `ProjectCollectionContext`는 이미 `Map<CollectionProvider, RawFetchRequest>`라 provider를 열거하지
  않고 순회할 수 있다 — 자료구조 변경은 필요 없다.
- 오케스트레이션 계층 수정이므로 **커넥터 PR에 섞지 않고 선행 PR로 뺀다**(체크리스트의 "공용 코드를
  고쳐야 한다면 먼저 상의한다" 케이스를 문서로 합의하는 것이 이 절이다).

## 3. backend — 연결

`com.history.backend.googlechat` 패키지(신규). SPI **4개 모두** 구현하는 첫 provider다 —
Jira(선택+갱신+폐기)에 없던 조합은 아니지만, 대화 아키타입에서는 처음이다.

- `IntegrationProvider.GOOGLE_CHAT("google-chat", "Google Chat")` 추가. **DB 마이그레이션 불필요**
  (V12에서 provider CHECK 제약 제거).
- `GoogleChatProperties`(`@ConfigurationProperties`) + `application.yaml`(운영·테스트 양쪽) 블록.
  **`PropertiesConfig`의 `@EnableConfigurationProperties` 목록에 등록하는 것을 빠뜨리지 않는다** —
  빠뜨리면 컨텍스트 로드 테스트가 통째로 죽는다(Discord에서 밟은 지점).
- `GoogleChatClient` — code 교환(`POST https://oauth2.googleapis.com/token`), refresh, grant 폐기
  (`POST https://oauth2.googleapis.com/revoke`), `spaces.list`, `spaces.get`.
- 자격증명은 Jira와 같은 JSON: `access_token` · `refresh_token` · `expires_at`.
  코덱은 Jira 것을 공용화하지 말고 `GoogleChatCredential`이 소유한다(형태가 우연히 같을 뿐이다).
- `GoogleChatOAuthConnectFlow` — 동의 URL(`access_type=offline`·`prompt=consent`·scope 2종·state),
  `exchangeCode`는 `OAuthConnection.pendingSelection(자격증명 JSON)` 반환(선택 단계가 있으므로
  이 시점에는 수집 대상 참조가 없다).
- `GoogleChatSelectionFlow`(`IntegrationSelectionFlow`) — **1단**:
  `SelectionStep.required("space_id", "space_name", "스페이스")`.
  options는 `spaces.list?filter=spaceType = "SPACE"` → `value = spaces/{id}`(리소스 이름 원문),
  `label = displayName`. `space_id`·`space_name`이 그대로 `external_ref` 키가 되고 pipeline-worker가
  같은 키를 읽는다(§4와 합의된 계약).
  - **`spaceType = "SPACE"`로 거르는 이유**: `spaces.list`는 사용자가 속한 DM·그룹 채팅까지 돌려준다.
    이름 있는 스페이스만 후보로 올려야 개인 DM이 프로젝트 그래프에 섞이지 않는다.
  - value에 리소스 이름 원문(`spaces/AAAA...`)을 담는 이유: 수집이 `GET /v1/{space_id}/messages`로
    바로 이어져 worker가 접두사를 조립할 필요가 없다.
- `GoogleChatAccessTokenRefresher`(`AccessTokenRefresher`) — `expires_at` 임박 시 refresh.
  **Google의 갱신 응답에는 `refresh_token`이 없다**(회전하지 않는다). 응답에 없다고 저장값을 비우면
  다음 갱신이 영구 실패하므로 **기존 refresh token을 그대로 유지**한다 — Atlassian(회전)·Microsoft(회전)와
  정반대라 Jira 코드를 복사하면 여기서 깨진다. 단위 테스트로 "갱신 후에도 refresh token이 보존된다"를 고정한다.
  refresh token이 폐기·만료(6개월 미사용, 또는 Testing 상태의 7일)로 영구 실패하면 Jira와 동일하게
  연동을 pending으로 되돌린다.
- `GoogleChatCredentialLifecycle`(`ProviderCredentialLifecycle`) — `POST /revoke?token={refresh_token}`.
  Google은 grant 단위로 폐기하므로 파생 access token도 함께 무효화된다. `externalRef`는 쓰지 않는다
  (Discord와 달리 남겨질 봇이 없다). 실패는 삼킨다(공용 규약).
- `IntegrationResponse.displayName` switch에 `GOOGLE_CHAT` case — `selectionValue("space_name")`.
  1단이라 상위 단계 병기는 없다. (exhaustive switch라 추가하지 않으면 컴파일이 깨진다.)
- 검증: `./gradlew test` — 선택 플로우는 A4 때 만든 1단형 테스트 패턴 재사용.

### 트레이드오프 — 스페이스 1개 제한

A4의 선택 단계는 **단계당 단일 선택**이다(`SelectionStep`에 다중 선택 개념이 없다). 따라서 프로젝트
하나에 Google Chat 스페이스 하나가 붙는다. Google Chat의 space는 Slack의 채널·Discord의 채널에
가까운 단위라, "팀이 여러 스페이스를 쓰면 하나만 수집된다"는 제약이 실제로 생긴다.

그럼에도 1차는 이걸로 간다.

- 대안 ①(선택 없이 전체 자동 수집, Slack형)은 **개인정보 측면에서 위험하다.** 사용자 인증이라
  `spaces.list`가 그 사람이 속한 회사 전체 스페이스와 DM을 돌려주므로, 프로젝트와 무관한 대화가
  그래프에 들어온다. Slack은 워크스페이스 스코프 봇 토큰이라 사정이 다르다.
- 대안 ②(다중 선택 지원)는 A4 인터페이스 확장이라 **공용 코드 변경**이다. 커넥터 PR에서 할 일이
  아니고, 필요해지면 별도 안건으로 올린다(같은 요구가 Slack 채널 한정 수집에서도 나올 수 있으니
  그때 함께 설계하는 편이 낫다).

## 4. pipeline-worker — 수집

`source/googlechat` 패키지(신규)에 `GoogleChatCollector` · `GoogleChatRawService` ·
`GoogleChatNormalizer` · `GoogleChatRateLimiter`. `CollectionProvider.GOOGLE_CHAT("google-chat")`
추가 외에 오케스트레이션 계층은 §2 선행 PR 이후 무변경이다.

### 수집 흐름

```
resolveFetchRequest: 자격증명 JSON 복호화 → access_token Bearer
                     external_ref.space_id 해석 (누락 → IllegalStateException)
collect:
  GET /v1/{space_id}                                   # displayName 1회 조회(채널 이름 최신화)
  반복: GET /v1/{space_id}/messages
          ?filter=createTime > "{checkpoint RFC-3339}"
          &orderBy=createTime ASC
          &pageSize=1000
          &pageToken={직전 응답의 nextPageToken}
  → normalize → publish → 최대 occurredAt으로 checkpoint 갱신
```

- **증분이 서버사이드 한 방에 끝난다.** `filter`는 `createTime`(RFC-3339)에 `>`·`<`를 지원하고
  기본 정렬이 `createTime ASC`라, checkpoint `Instant`를 그대로 문자열로 넣고 앞으로만 페이지를
  넘기면 된다. Slack처럼 히스토리를 되짚을 필요도, Discord처럼 `before`로 방향을 뒤집을 필요도 없다.
  `>`가 strict이므로 경계 메시지가 중복 발행되지도 않는다.
- **스레드 답글을 위한 별도 호출이 없다.** `spaces.messages.list`는 루트와 답글을 모두 돌려주고
  각 메시지가 `thread.name`을 들고 있다. Slack의 `conversations.replies`, Teams의 `$expand=replies`에
  해당하는 2차 호출이 없어 호출 수가 채널 수와 무관하게 페이지 수로만 결정된다.
- checkpoint: `google-chat/google_chat_messages` 단일 커서. 스페이스가 하나라 Slack·Discord처럼
  "채널을 가로질러 마지막에 한 번" 갱신할 이유도 없지만, 배치의 최대 `occurredAt`으로 갱신하는
  공용 규약은 그대로 따른다(발행 예외 시 전진하지 않아야 재발행된다).
- 웹훅 사이클 편입: 다른 소스처럼 GitHub PR 머지 웹훅에 앵커된다(의도된 설계). Google Workspace
  Events API 구독은 후보로도 두지 않는다 — 별도 수신 엔드포인트·구독 갱신 주기 관리가 붙는다.
- **수정된 메시지는 추적하지 않는다.** `filter`가 `createTime`만 지원하고 `lastUpdateTime`은 필터
  대상이 아니라, 편집분을 다시 긁을 서버사이드 수단이 없다. Slack·Discord와 같은 수준의 제약이며
  대화 아키타입 공통 과제로 남긴다(Teams만 정렬 덕분에 편집 추적이 가능한 예외였다).
- 삭제된 메시지는 `showDeleted`를 켜지 않아 기본적으로 제외된다.

### NormalizedEvent 매핑 (`docs/normalized-event.md` 계약)

| 계약 필드 | Google Chat 값 | 비고 |
|-----------|---------------|------|
| `source` | `GOOGLE_CHAT` | |
| `properties.url` (자연키) | `message.name`(`spaces/{space}/messages/{id}`)에서 **조립** | Chat API는 permalink 필드를 주지 않는다. `name`이 고유·결정적이라 자연키 역할은 충족한다. 사람이 클릭 가능한 형태로 만들 수 있는지는 「착수 전 실측」 3번 |
| `properties.body` | `text` | 이미 평문이다(Teams의 HTML 변환 불필요). 멘션도 `text`에 표시 이름으로 들어오므로 Discord식 `<@id>` 치환이 필요 없다 — 실측으로 확인한다 |
| `properties.channel` | `spaces.get`의 `displayName` | `external_ref.space_name`을 쓰지 않고 매 수집 1회 조회한다 — 스페이스 이름이 바뀌어도 따라간다 |
| `properties.conversation_id` | `thread.name` | 루트·답글이 같은 값을 공유한다. **대화 아키타입 중 가장 단순한 매핑**(Slack·Discord는 분기가 필요했다) |
| `properties.created_at` · `occurredAt` | `createTime` | `lastUpdateTime`은 쓰지 않는다 — 커서를 되돌리지 않기 위함 |
| `actor.id` | `sender.name`의 `users/` 뒤 id | 안정적·고유. 표시 이름을 id로 쓰지 않는다 |
| `actor.name` | `sender.displayName` | `isAnonymous`(탈퇴·비공개 프로필)면 빈 값일 수 있다 |
| `actor.email` | **`null`** | `User` 리소스에 email이 없다. §7 |
| `refs` | `text`에 `RefsExtractor` 그대로 | 평문이라 전처리가 필요 없다 |

정규화 제외: `sender.type == "BOT"`(앱·웹훅 메시지), `deletionMetadata`가 있는 메시지,
`text`가 비어 있는 메시지(카드·첨부만 있는 경우 — 임베딩할 본문이 없다).
Slack·Discord normalizer의 관례와 같다.

### Rate limit

`GoogleChatRateLimiter` — 메시지·스페이스 읽기 쿼터는 **Cloud 프로젝트당 60초에 3,000요청**으로
넉넉하다. 다만 이 쿼터는 **우리 앱을 쓰는 모든 사용자가 공유**한다(Discord의 봇당 한도와 달리
사용자 수만큼 늘지 않는다). 그래서 초기값은 호출당 100ms 고정 딜레이로 두고, 429에는 문서 권고대로
**지수 백오프**(`min((2^n + jitter), max)`)로 최대 3회 재시도한다. 429가 잦아지면 딜레이가 아니라
쿼터 증설이나 프로젝트 분리를 검토한다.

## 5. web-dashboard — 화면

`sourceCatalog.tsx`의 `google-chat` 항목(브랜드 마크 `GoogleChatMark`와 함께 이미 있다)을
`status: "wired"`로 바꾸고 `connect: "oauth"`와 `deletedData`(예: "수집한 스페이스 메시지·스레드와
그 그래프 연결")를 채우면 끝이다. 스페이스 선택 폼은 backend의 1단 선언을 `OAuthSourceCard`가
그대로 렌더하므로 **provider 전용 컴포넌트를 만들지 않는다.**
검증: `npm run typecheck && npm run build`.

## 6. ai-engine — 무변경

`Communication` + `source=GOOGLE_CHAT`으로 정규화되므로 코드 변경이 없다. 소스별 삭제
(`DELETE /graph/projects/{id}/sources/GOOGLE_CHAT`)·Actor alias(`GOOGLE_CHAT:{id}`)·Slack 노이즈
필터가 source 문자열 기반으로 자동 적용된다.

표시 라벨도 **등록이 필요 없다** — `graph/overview.py`의 `_source_label`이 대문자 snake에서
`GOOGLE_CHAT` → `Google Chat`으로 유도하며, 이 케이스가 주석의 예시로 이미 적혀 있다.
routing key도 `EventPublisher`가 `event.google_chat`으로 유도한다(같은 예시가 주석에 있다).
확인은 스모크 테스트 하나면 된다.

## 7. 개인정보 — 이메일 없음(Discord와 같은 처지)

Chat API의 `User` 리소스는 `name`·`displayName`·`domainId`·`type`·`isAnonymous`뿐이고 **email이
없다.** 따라서 1차 범위에서 `actor.email`은 항상 `null`이며, 동일인 판단이 표시 이름에만 의존한다.
`docs/actor-node-design.md`의 스코어링에서 이메일이 가장 강한 신호이므로, Google Chat Actor는
다른 소스의 동일인과 자동으로 묶이지 않을 가능성이 높다 — Discord와 마찬가지로
`docs/actor-manual-merge.md`의 **수동 병합이 예외가 아니라 정상 경로**라고 보고 연동 후 안내한다.

2차 옵션으로 People API 보강 경로가 있다: `directory.readonly` scope로 같은 도메인 프로필의
`emailAddresses`를 조회한다. **1차에서는 넣지 않는다** — scope가 하나 늘어 동의 화면이 무거워지고,
`sender.name`의 id가 People API의 `people/{id}`와 같은 값인지 실측되지 않았다(「착수 전 실측」 4번).
넣는다면 Slack user map처럼 TTL 캐시로 등장한 id만 조회한다.

이름·이메일은 기존대로 `ActorAlias.pd_*`에만 저장한다. Atlassian식 개인정보 보고 의무는 없다.

## 8. 문서 동반 갱신 (커넥터 PR에 포함)

- `docs/data-collection.md` — Google Chat 섹션(수집 대상·`createTime` 증분·rate limit·트레이드오프).
- `docs/integration-abstraction.md` — Part B 표의 Google Chat 완료 표시, §5-2 표기 항목 확정 반영.
- `services/backend/CLAUDE.md`(패키지 구조에 `googlechat`, SPI 4종 구현 provider로 기재) ·
  `services/pipeline-worker/CLAUDE.md`(`source.googlechat` 행, 라우팅 표, checkpoint 목록, rate limit).
- `docs/graph-schema.md`·`docs/DB.md`는 변경 없음(새 노드·테이블 없음)을 확인만 한다.

## 9. 검증 계획

- 단위: backend `./gradlew test` / pipeline-worker `./gradlew test` / 프론트 `typecheck && build`.
- 선행 PR(§2-0): ✅ 완료 — `PipelineSharedSchemaTest`에 새 provider 값 저장 가능 회귀 테스트 반영됨.
- 선행 PR(§2-1): "404 → 저장 자격증명으로 진행(Slack·Discord 보존)" 회귀 테스트 필수.
- **갱신 시 refresh token 보존 단언** — Google은 갱신 응답에 refresh token을 주지 않는다.
  Jira 코드를 본떠 쓰면 조용히 null로 덮어써 다음 갱신부터 영구 실패하므로, 단위 테스트로 고정한다.
- **본문 존재 단언** — 정규화 테스트에서 `body`가 비지 않음을 확인한다(Discord의 MESSAGE_CONTENT
  같은 조용한 실패를 대화 아키타입 공통으로 방어).
- 실기동 시나리오: 연결 → 스페이스 선택 → 초기 수집 → 그래프에 GOOGLE_CHAT Communication 확인 →
  스레드 답글이 같은 `conversation_id`로 묶이는지 → 새 메시지 추가 후 PR 머지 웹훅으로 증분
  (**1시간 뒤 토큰 갱신 경로 포함** — §2 선행 PR이 실제로 도는지 보는 유일한 지점) →
  해제 시 GOOGLE_CHAT 노드만 삭제되고 Google 계정의 앱 권한도 사라지는지 확인.

## 착수 전 실측 (미확정 — 여기서 막히면 계정 경로부터 갈아탄다)

1. **계정 게이트**(§1-0) — 후보 계정으로 Chat API 사용 설정이 되는지, Essentials Starter 같은 무료
   에디션에서도 API가 열리는지. **다른 모든 항목보다 먼저 본다.**
2. **사용자 인증만 쓸 때도 Chat 앱 구성이 필요한지** — 구성 페이지(이름·아바타)를 채우지 않아도
   `spaces.list`·`messages.list`가 도는지. 필요하다면 §1-1에 필수 절차로 승격한다.
   함께 볼 것: 사용자 인증 읽기에 **Chat 앱이 그 스페이스에 설치돼 있어야 하는지**(문서가 명시하지
   않는다). 필요하다면 Discord처럼 "앱을 스페이스에 추가" 단계가 연결 UX에 생긴다.
3. **메시지 permalink 형식** — UI의 '링크 복사'가 만드는 URL 형태를 확인해 `properties.url`을
   사람이 클릭 가능한 값으로 만든다. 불가능하면 `name` 기반 결정적 문자열을 그대로 쓴다
   (자연키 역할에는 지장이 없다).
4. **`sender.name`의 id ↔ People API `people/{id}` 동일성** — §7의 email 보강 가능 여부가 여기 달렸다.
5. **`text`의 멘션 표기** — 표시 이름으로 들어오는지, 별도 치환이 필요한지(`annotations`의
   `USER_MENTION`은 `text` 기준 `startIndex`·`length`를 준다). Discord는 `<@id>` 치환이 필요했다.
6. **`pageSize` 상한 실효값** — 문서상 최대 1000이지만 실제로 얼마나 채워 오는지 본다.

## 10. 미리 정한 것 — provider 표기 (`integration-abstraction.md` §5-2 종결)

두 단어 provider의 표기를 Google Chat로 확정한다. 새로 만드는 규칙이 아니라, 이미 코드 주석과
`docs/normalized-event.md`가 **예시로 쓰고 있던 형태를 그대로 채택**하는 것이다.

| 계층 | 값 |
|------|-----|
| RDB `integrations.provider`, HTTP 경로 | `google-chat` |
| `NormalizedEvent.source`, Neo4j `source` | `GOOGLE_CHAT` |
| Actor alias 접두 | `GOOGLE_CHAT:` |
| RabbitMQ routing key | `event.google_chat` |
| Java 패키지 | `googlechat` (구분자 없음 — 패키지명 규칙) |

경로만 kebab, 나머지는 snake인 것이 헷갈릴 수 있으나 **계층별 관례를 그대로 따른 결과**이며,
`EventPublisher`(소문자 변환)와 `_source_label`(snake → 단어) 양쪽이 이 조합을 이미 예시로 다룬다.

## 참고 (Google Chat API v1, 2026-08 조사)

- List messages(필터·정렬·scope): developers.google.com/workspace/chat/api/reference/rest/v1/spaces.messages/list
- List spaces(spaceType 필터): developers.google.com/workspace/chat/api/reference/rest/v1/spaces/list
- User 리소스(email 없음): developers.google.com/workspace/chat/api/reference/rest/v1/User
- 인증 모델(앱 인증 vs 사용자 인증): developers.google.com/workspace/chat/authenticate-authorize
- 쿼터(3,000/분·429 백오프): developers.google.com/workspace/chat/limits
- Workspace 계정 요건 오류: developers.google.com/workspace/chat/troubleshoot-chat-apps
- refresh token 수명(Testing 7일·6개월 미사용): developers.google.com/identity/protocols/oauth2
- Essentials Starter(무료 에디션 요건): support.google.com/a/answer/7681288
