# MS Teams 연동 계획 — 대화 아키타입 2호

`docs/integration-abstraction.md` Part B의 대화 아키타입 커넥터다. Slack이 레퍼런스 구현이며,
ai-engine은 무변경이다(`Communication` 노드 재사용). 전체 순서는 「커넥터 엔드투엔드 체크리스트」를
따르고, 이 문서는 그 체크리스트를 Teams에 대입했을 때의 **결정 사항과 Teams 고유 설계**만 다룬다.

Microsoft Graph API 조사(2026-08, v1.0 문서 기준)를 근거로 작성했다. 미확정 항목은 맨 아래
「구현 시 확인」에 모았다.

> **착수 보류 (2026-08-08 결정, 2026-08-10 실측으로 재확인).** 대화 아키타입 1호를 Discord로
> 바꾸고 Teams는 2호로 미뤘다. 착수 보류 근거가 2026-08-10 실측으로 **더 명확해졌다** — 애초
> "유료 조직 테넌트 라이선스"가 장벽이라고 봤는데, 실측해보니 **라이선스는 학교 같은 교육기관
> 계정(Office 365 A1)이면 무료로도 통과된다.** 진짜 장벽은 그다음 단계인 **테넌트 관리자 동의**였고,
> 이건 라이선스를 사든 안 사든 **일반(비관리자) 사용자 전원에게 동일하게 적용되는 구조적 제약**이라는
> 게 이번에 확정됐다 — 상세는 §1-0 하단과 §1-2. **이 문서의 설계는 그대로 유효하며**, §1-1부터
> 바로 착수할 수 있다. 단 §2(webhook 토큰 확보 일반화)는 Teams 전용이 아니므로 Discord·Google Chat
> 진행 중에 이미 선행 PR로 처리됐다.

## 0. 결정 사항 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| provider 표기 | RDB/경로 `teams` · source `TEAMS` · alias `TEAMS:{aadUserId}` · routing `event.teams`(자동 유도) | 카탈로그 id(`teams`)·기존 빈 패키지 이름과 일치. 한 단어라 표기 충돌 없음 |
| API | Microsoft Graph **v1.0만** 사용 | delta를 쓰지 않아 beta 의존이 없다 (아래 §4 증분 전략) |
| 연결 플로우 | OAuth(authorization code) → **1단 선택(team)** → 확정 | `/me/joinedTeams`로 후보 조회. A4 다단 선택 메커니즘 재사용 — 추상화 문서의 "대화형도 선택 단계를 재사용할 수 있는가"가 **가능(1단)으로 확정**된다 |
| 수집 범위 | 선택한 팀의 **전체 채널**(사용자가 접근 가능한 채널). chat(개인·그룹 DM)은 제외 | 채널 자동 수집은 Slack 철학과 동일. chat 읽기는 delegated 미지원 + metered API라 범위 밖 |
| 토큰 | 만료+갱신형 — `AccessTokenRefresher` **구현** | access token ~1시간, refresh token은 사용 시 교체(회전). `offline_access` scope 필수 |
| 원격 폐기 | `ProviderCredentialLifecycle` **구현하지 않음** | Microsoft identity platform에는 앱 주도 개별 토큰 폐기 endpoint가 없다(`revokeSignInSessions`는 사용자 전체 세션 폐기라 과격). 해제는 DB·그래프 삭제만 |
| 선행 공용 변경 | ~~webhook 경로 토큰 확보의 Jira 하드코딩 일반화 (§2)~~ ✅ 완료 | Google Chat이 "만료 토큰을 쓰는 두 번째 provider"가 되면서 이미 일반화됐다 — Teams는 이 선행 조건이 필요 없다 |
| **진입 장벽** | 두 단계로 갈린다 — ① 라이선스(교육기관 계정은 **무료로 통과 가능**, 실측 확인) ② **테넌트 관리자 동의**(라이선스와 무관하게 비관리자 사용자 전원을 막음, 실측 확인) | Teams Graph API는 라이선스로 먼저 게이트되고(개인 계정은 우회 불가), 그다음 관리자 동의로 다시 게이트된다. **①만 뚫려도 ②가 남는다** — 이게 개발 계정 문제가 아니라 제품 채택률 문제인 이유(§1-0·§1-2) |

## 1. 사전 준비 — 테넌트·라이선스 확보와 Entra ID 앱 등록

### 1-0. 선행 조건 — 라이선스가 붙은 조직 계정 (2026-08-08 실측)

**Teams Graph API는 scope가 아니라 라이선스로 먼저 게이트된다.** 개인 Microsoft 계정으로는
어떤 우회도 없으며, 이는 개발 환경뿐 아니라 **제품의 최종 사용자에게도 그대로 적용되는 제약**이다.

개인 계정(`@outlook.com`·`@gmail.com`으로 만든 MSA)에서 실측한 응답:

```
GET /v1.0/me/joinedTeams
  401 Unauthorized — "Invoked API requires a valid license. No valid license found."
GET /v1.0/organization
  400 BadRequest — "This API is not supported for MSA accounts."
```

계정 종류는 `GET /v1.0/me`로 판별한다. **`id`가 16자리 hex(`e91588504afe2862`)면 개인 계정이고,
GUID면 조직 계정이다.** `/organization`이 위 오류를 내면 소속 테넌트가 아예 없다는 뜻이다.

| 상태 | 필요한 조치 |
|------|------------|
| 개인 MSA (테넌트 없음) | 조직 테넌트를 새로 만들어야 한다. M365 Business Basic 1개월 체험이 현실적 |
| 게스트(`..._outlook.com#EXT#@...`) | **게스트에는 라이선스를 할당할 수 없다** — 정식 멤버 계정이 필요 |
| 조직 계정 + 라이선스 없음 | 라이선스 구매·할당만 하면 된다 |

라이선스 구매 시 함정: Microsoft가 2024-04 규제 대응으로 Teams를 분리해 **"Teams 없는" 요금제**를
따로 팔기 시작했고 2025-11부터 포함 번들이 복귀해, 지금은 두 변형이 공존한다.
**Teams 포함 여부를 반드시 확인하고 구매한다** — 아니면 라이선스를 사고도 같은 401을 다시 만난다.

M365 Developer Program 샌드박스는 2024년부터 Visual Studio Professional/Enterprise 구독자와
파트너 프로그램 가입사로 제한되어 일반적인 무료 경로가 아니다.

### 1-0-1. 추가 실측 (2026-08-10) — 교육기관 계정으로 라이선스 게이트 무료 통과, 관리자 동의에서 확정 차단

Google Chat 실측 중 확보한 서울과기대 Microsoft 365 계정(`@officestu.seoultech.ac.kr`, **학생용
Office 365 A1** 라이선스 — 교육기관에 무료 제공되는 티어)으로 위 §1-0의 라이선스 게이트를 다시
찔러봤다. **A1에는 Teams가 기본 포함돼 있어 라이선스 구매 없이 게이트를 통과했다** — §1-0의
가정("유료 조직 테넌트 필요")보다 나은 결과다.

Graph Explorer로 실측한 순서와 결과:

```
GET /v1.0/me/joinedTeams
  → 200 OK, {"value": []}                      # 라이선스 게이트 통과. 가입한 팀이 없어 빈 배열
(Teams 앱에서 테스트 팀 "history tracker test" 생성 후 재호출)
GET /v1.0/me/joinedTeams
  → 200 OK, {"value": [{ "id": "...", "displayName": "history tracker test", ... }]}
GET /v1.0/teams/{team-id}/channels
  → 200 OK, "General" 채널 반환                  # Channel.ReadBasic.All, admin consent 불필요 — 그대로 통과
GET /v1.0/teams/{team-id}/channels/{channel-id}/messages
  → Graph Explorer "Modify Permissions"에서 ChannelMessage.Read.All 동의 시도
  → "관리자 승인이 필요합니다(Need admin approval)" 화면 — 동의 자체가 불가, "승인 요청"만 가능
```

**결론**: §1-0이 예상한 "라이선스"와 "관리자 동의"를 하나의 장벽으로 뭉뚱그렸었는데, 실측해보니
**서로 다른 두 장벽**이었다. 라이선스는 교육기관 계정으로 무료 우회가 가능하지만, `ChannelMessage.Read.All`의
관리자 동의는 라이선스와 무관하게 **비관리자 사용자 전원을 막는다** — 이 계정이 소속 테넌트에서
관리자 권한이 없어 "승인 요청"만 가능하고 스스로 동의를 내릴 수 없었다. 상세 함의는 §1-2.

### 1-1. Entra ID 앱 등록

- 앱 유형: **multi-tenant** ("Accounts in any organizational directory"). Teams는 조직(work/school)
  계정 전용이므로 authority는 `https://login.microsoftonline.com/organizations/oauth2/v2.0/…`를 쓴다
  (personal 계정 허용 불필요).
- redirect URI: `{BASE}/api/v1/integrations/teams/callback` — 소문자 kebab, 이후 변경 불가.
  **Entra는 localhost에 한해 http를 허용**하므로 로컬 개발에 터널이 필요 없다
  (`http://localhost:5173/api/v1/integrations/teams/callback`). `.env.example`이 Slack·Jira에
  안내하는 터널 도메인은 Teams에는 해당하지 않는다.
  **포트는 backend(:8080)가 아니라 프론트(:5173)다** — `IntegrationOAuthController.callback`이
  `/projects/...`로 **상대 경로** 302를 돌려주므로, 브라우저는 이 콜백 요청이 도착한 origin
  기준으로 그 경로를 해석한다. redirect URI를 :8080으로 등록하면 최종 302가
  `localhost:8080/projects/...`로 풀려 401로 끝난다(Discord에서 실측 확정된 함정 —
  `docs/discord-integration.md` 「확인 완료」 2). :5173으로 등록해야 하는 이유는 Vite dev
  proxy(`vite.config.ts`)가 `/api/*`를 :8080으로 투명하게 전달하면서도 브라우저의 origin은 계속
  :5173에 머물게 하기 때문이다 — 그래야 상대 302가 프론트 SPA로 정확히 돌아온다.
- delegated scope (최소 권한):

  | scope | 용도 | admin consent |
  |-------|------|---------------|
  | `openid profile offline_access` | id_token + **refresh token 발급** | 불필요 |
  | `Team.ReadBasic.All` | `/me/joinedTeams` — 선택 단계 후보 | 불필요 |
  | `Channel.ReadBasic.All` | 팀의 채널 목록 | 불필요 |
  | `ChannelMessage.Read.All` | 채널 메시지·답글 읽기 | **테넌트 관리자 동의 필요** |
  | `User.ReadBasic.All` | actor email 보강(`/users/{id}` — `mail`) | 불필요 |

- **admin consent가 연결 UX의 관문이다.** 일반 사용자가 연결하면 Microsoft가 "관리자 승인 필요"
  화면을 띄우고 콜백에 `error=access_denied`(또는 `consent_required`)로 돌아온다.
  콜백 302의 `?error=` 코드로 매핑해 화면에 "테넌트 관리자 동의가 필요합니다"를 안내한다.
  운영 절차(관리자 동의 URL `https://login.microsoftonline.com/organizations/adminconsent?client_id=…`
  또는 관리자 계정으로 최초 연결)는 배포 문서에 남긴다 — `docs/jira-personal-data-policy.md`의
  봇 계정 등록 절차와 같은 성격의 배포 시 1회 절차다.
- 환경변수(`ATLASSIAN_*` 패턴): `TEAMS_CLIENT_ID` · `TEAMS_CLIENT_SECRET` · `TEAMS_REDIRECT_URI`.
  `infra/docker/docker-compose.yml` backend 블록에 추가, 실제 값은 `.env`.

### 1-2. 일반 사용자 채택 한계 (2026-08-10 실측으로 확정 — 개발 계정만의 문제가 아니다)

§1-0-1에서 확인한 사실이 만드는 실제 함의를 짚는다. **"라이선스를 사면(또는 이미 있으면) 연결된다"는
가정이 틀렸다.** 라이선스와 관리자 동의는 서로 다른 축이다.

| 그 사용자가… | 결과 |
|---|---|
| 라이선스 있음 + 소속 조직의 관리자 아님 + 그 조직이 우리 앱을 사전 승인 안 함 | **막힘** — §1-0-1에서 실측한 그대로. 라이선스 유무는 무관하다 |
| 라이선스 있음 + 소속 조직의 관리자임(Global Admin 등) | 통과 — 스스로 즉시 동의 가능 |
| 라이선스 있음 + 관리자 아님 + 그 조직 IT가 우리 앱을 이미 사전 승인함 | 통과 — 조직 단위 동의는 그 조직 구성원 전체에 적용된다 |

`ChannelMessage.Read.All`은 Microsoft가 **모든 테넌트에 대해 전역적으로** "관리자 동의 필수"로
분류해 둔 scope라, 개별 사용자나 우리 쪽에서 이 분류를 바꿀 방법이 없다. 즉:

- **셀프서비스 가입 흐름과 근본적으로 안 맞는다.** 다른 provider(Slack·Jira·Discord·Google Chat)는
  사용자 본인이 "연결" 버튼만 누르면 끝나는데, Teams는 **일반 사용자 대다수(관리자가 아닌 대부분의
  구성원)가 자기 조직 IT의 사전 승인 없이는 스스로 못 넘는 벽**을 만난다.
- **"우리가 도메인을 사서 자체 테넌트를 만들면 되지 않나"는 우리 쪽 개발·테스트 편의만 해결한다.**
  자체 테넌트를 만들면 만든 사람이 Global Admin이 되어 관리자 동의를 스스로 낼 수 있지만(§1-0-1의
  "승인 요청" 벽이 우리 개발 계정에서는 사라진다), **그건 우리가 검증할 때 이야기고, 실제 최종
  사용자는 여전히 각자 자기 조직에서 이 벽을 만난다.** 즉 도메인 구매는 개발 단계의 마찰을
  줄여줄 뿐 제품 채택률 문제를 해결하지 않는다.
- 현실적인 채택 경로는 둘뿐이다: ① 그 조직의 관리자가 직접 연결(첫 사용자가 관리자이거나, 관리자에게
  대신 연결해 달라고 요청) ② 조직 IT가 사전에 조직 전체 동의를 내려줌(엔터프라이즈 영업/온보딩
  성격의 절차 — Jira의 `docs/jira-personal-data-policy.md` 봇 계정 등록처럼 배포 시 1회성 관리
  작업으로 다룰 수 있지만, **고객사마다 반복해야 한다**는 점이 다르다).

착수 보류 결정(맨 위 인용문)은 이 발견으로 오히려 강화된다 — 단순히 "라이선스 비용" 문제였다면
누군가 돈을 내면 해결됐겠지만, 실제로는 **관리자 동의라는 조직 정책 문제**라 개인 사용자 대상
셀프서비스 제품에는 구조적으로 안 맞는다. 다시 착수한다면 이 제약을 안고 갈지(관리자 온보딩 전제),
아니면 대화 아키타입은 Slack·Discord·Google Chat 세 개로 충분하다고 보고 Teams를 아예 접을지를
먼저 정해야 한다.

## 2. 선행 공용 변경 — webhook 토큰 확보 일반화 (✅ 완료, 2026-08-09 — Google Chat 작업 중 처리됨)

`GitHubWebhookService.ensureFreshJiraToken` + `JiraTokenClient`가 Jira 하드코딩이었다. Google Chat이
Jira 외의 두 번째 만료 토큰형 provider가 되면서 이 절의 설계 그대로 처리했다 —
`docs/google-chat-integration.md` §2-1 참고. **Teams가 착수될 때는 이미 해결된 상태이므로 아래는
과거 설계 기록으로만 남긴다.**

backend 내부 API는 이미 범용(`/api/v1/internal/integrations/{projectId}/{provider}/token`)이므로 호출부만 일반화한다.

- `JiraTokenClient` → `IntegrationTokenClient.ensure(projectId, provider)`로 개명·범용화.
  결과는 3값으로 구분한다: **REFRESHED**(204) / **NOT_SUPPORTED**(404) / **FAILED**(그 외·예외).
- webhook context 조립 시 앵커(GitHub)를 제외한 context 내 모든 provider에 대해 ensure를 호출한다.
  - REFRESHED → 해당 provider의 fetch request 재해석(현행 Jira 동작).
  - NOT_SUPPORTED → **저장된 자격증명 그대로 진행.** context에 이미 있는 provider이므로 404는
    "연동 없음"이 아니라 "갱신 수단 없음(비만료형)"이다 — Slack이 여기 해당하며, 오늘의
    'Slack은 ensure 없이 진행' 동작이 보존된다. 404를 '수집 제외'로 해석하면 Slack이 끊긴다
    (조용한 204 사건의 거울상이니 테스트로 고정한다).
  - FAILED → 해당 provider만 제외하고 진행(현행 Jira 동작과 동일 — 죽은 토큰으로 401을 내지 않는다).
- 이건 오케스트레이션 계층 수정이므로 **커넥터 PR에 섞지 않고 선행 PR로 뺀다**
  (체크리스트의 "공용 코드를 고쳐야 한다면 먼저 상의한다" 케이스를 문서로 합의하는 것이 이 절이다).

## 3. backend — 연결

`com.history.backend.teams`(빈 자리 마련됨)에 구현. `services/backend/CLAUDE.md` 「provider 전략」의
SPI 중 **3개만** 구현한다.

- `IntegrationProvider.TEAMS("teams", "MS Teams")` 추가. DB 마이그레이션 불필요(V12에서 CHECK 제거).
- `TeamsProperties`(@ConfigurationProperties) + `application.yaml` 블록, `TeamsClient`
  (code 교환·refresh·joinedTeams·channels·users 조회).
- 자격증명은 Jira와 같은 JSON 형태: `access_token` · `refresh_token` · `expires_at`
  (+ 선택: `tenant_id` — id_token의 `tid`. 지금은 소비처가 없으니 필요해질 때 넣어도 된다).
  코덱은 Jira 것을 공용화하지 않고 `TeamsCredential`로 소유한다(형태가 우연히 같을 뿐).
- `TeamsOAuthConnectFlow` — 동의 URL 조립(`organizations` authority), `exchangeCode`는
  `OAuthConnection.pendingSelection(자격증명 JSON)` 반환(선택 단계가 있으므로 수집 대상 참조 없음).
- `TeamsSelectionFlow`(`IntegrationSelectionFlow`) — 1단:
  `SelectionStep.required("team_id", "team_name", "팀")`. options는 `/me/joinedTeams`.
  `team_id`·`team_name`이 그대로 external_ref 키가 되고 pipeline-worker가 같은 키를 읽는다.
- `TeamsAccessTokenRefresher`(`AccessTokenRefresher`) — `expires_at` 임박 시 refresh.
  Microsoft는 refresh 응답마다 **새 refresh token을 주므로 반드시 교체 저장**한다(옛 토큰이 즉시
  폐기되진 않지만 회전을 전제로 다룬다). 갱신 주체는 Jira처럼 backend 하나다 — pipeline-worker는
  내부 토큰 API로만 위임한다. refresh token이 폐기·만료(기본 90일 미사용)로 영구 실패하면
  Jira와 동일하게 연동을 pending으로 되돌린다.
- `ProviderCredentialLifecycle`은 **만들지 않는다**(§0 근거). 연동 해제 공용 흐름이 그래프·RDB를
  지우는 것으로 끝난다. 사용자 쪽 완전 철회는 myapps.microsoft.com 안내 문구로 해소(해제 다이얼로그).
- `IntegrationResponse.displayName` switch에 `TEAMS` case — `selectionValue("team_name")` 표시.
  1단이라 상위 단계 병기는 없다.
- 검증: `./gradlew test` — 선택 플로우는 A4 때 만든 테스트 패턴(1단형) 재사용.

## 4. pipeline-worker — 수집

`source/teams`(빈 자리 마련됨)에 `TeamsCollector` · `TeamsRawService` · `TeamsNormalizer` ·
`TeamsRateLimiter`. `CollectionProvider.TEAMS` 추가 외에 공용 코드는 §2 선행 PR 이후 무변경이다.

### 수집 흐름

```
resolveFetchRequest: 자격증명 JSON 복호화 → access_token Bearer
                     external_ref.team_id 해석 (누락 → IllegalStateException)
collect:
  GET /teams/{team_id}/channels                        # 채널 목록
  채널별: GET /channels/{id}/messages?$top=50&$expand=replies   # 루트+답글, 페이지 순회
  → normalize → publish → 전 채널 최대 occurredAt으로 checkpoint 1회 갱신
```

### 증분 전략 — delta 대신 정렬 기반 조기 종료

채널 메시지 목록은 **답글 체인 전체(루트+답글)의 lastModified 내림차순**으로 온다(v1.0 문서 명시).
따라서 페이지를 최신부터 순회하다가 `max(루트.lastModifiedDateTime, 최신 답글.lastModifiedDateTime)
< checkpoint`인 체인을 만나면 그 채널 순회를 끝내도 된다 — 오래된 스레드에 새 답글이 달리면 체인이
위로 떠오르므로 놓치지 않는다. **Slack 수집의 두 약점(조기 종료 불가, 오래된 스레드 답글 추적용
별도 후보 관리)이 정렬 하나로 구조적으로 해소된다.** $expand된 답글은 최신순이라 첫 항목으로 체인
최신성을 판정하고, 200개 초과분은 `replies@odata.nextLink`를 따른다.

Graph의 delta API를 쓰지 않는 이유: ① checkpoint 저장소가 `(project, provider, cursor_key) → Instant`
계약이라 opaque deltaToken을 담으려면 공용 스키마를 바꿔야 한다 ② delta는 최근 8개월 창 제한이 있어
초기 전체 수집을 별도 경로로 짜야 한다 ③ 채널 delta는 v1.0 안정성 이슈 보고가 있다. 정렬 기반
조기 종료가 같은 효과를 기존 계약 안에서 낸다.

- checkpoint: `teams/teams_messages` 단일 커서. Slack과 같은 이유로 채널을 가로질러 마지막 1회 갱신.
- 발행 대상: `createdDateTime ≥ checkpoint`인 루트·답글, 그리고 checkpoint 이후 **수정**된 메시지
  (재발행 — ai-engine upsert가 수정을 반영한다. 중복 발행은 멱등, 누락만 사고라는 계약 그대로).
- 웹훅 사이클 편입: 다른 소스처럼 GitHub PR 머지 웹훅에 앵커된다(의도된 설계 — 소스별 웹훅·구독을
  만들지 않는다). Graph change notification 구독은 후보로도 두지 않는다(암호화 요건·metered 계열).

### NormalizedEvent 매핑 (`docs/normalized-event.md` 계약)

| 계약 필드 | Teams 값 | 비고 |
|-----------|----------|------|
| `source` | `TEAMS` | |
| `properties.url` (자연키) | `webUrl` | 채널 메시지에는 항상 있다. 프로젝트 내 고유 |
| `properties.body` | `body.content` HTML → **평문 변환** | `<at id>` 멘션은 mentions 배열로 `@표시이름` 치환, 이미지·attachment 참조는 제거 |
| `properties.channel` | 채널 `displayName` | |
| `properties.conversation_id` | 루트: 자기 메시지 `id` / 답글: `replyToId` | |
| `properties.created_at` · `occurredAt` | `createdDateTime` | 수정 재발행이어도 `occurredAt`은 원 시각 — 커서는 뒤로 가지 않는다 |
| `actor.id` | `from.user.id` (AAD object id, GUID) | 안정적·고유. 표시 이름을 id로 쓰지 않는다 |
| `actor.name` | `from.user.displayName` | |
| `actor.email` | `/users/{id}?$select=mail` 보강 | 메시지에는 email이 없다. Slack user map처럼 TTL 캐시(`app.teams.user-map-cache-ttl`) — 전체 목록 대신 등장한 id만 조회 |
| `refs` | 평문 변환 후 `RefsExtractor` | 이슈 키(`ABC-123`)·PR 참조 정규식 그대로 적용 |

정규화 제외: `messageType != "message"`(시스템 이벤트), `deletedDateTime != null`(소프트 삭제),
`from.user == null`(봇·앱 메시지 — Slack normalizer 관례에 맞춘다).

### Rate limit

`TeamsRateLimiter` — 고정 딜레이 + **429의 `Retry-After` 준수**(Graph 공통 규약). Teams 계열 세부
상한은 Microsoft Learn의 throttling 문서(Teams service limits)로 구현 시 확정하고, 초기값은 보수적으로
호출당 250~300ms에서 시작한다.

## 5. web-dashboard — 화면

`sourceCatalog.tsx`의 `teams` 항목을 `status: "wired"`로 바꾸고 `connect: "oauth"`와 `deletedData`
(예: "수집한 Teams 채널 메시지·스레드와 그 그래프 연결")를 채우면 끝이다 — 브랜드 마크
(`MicrosoftTeamsMark`)와 항목은 이미 있다. 팀 선택 폼은 backend 단계 선언(1단)을 `OAuthSourceCard`가
그대로 렌더하므로 provider별 컴포넌트를 만들지 않는다. 검증: `npm run typecheck && npm run build`.

## 6. ai-engine — 무변경

`Communication` + `source=TEAMS`로 정규화되므로 코드 변경이 없다. 소스별 삭제
(`DELETE /graph/projects/{id}/sources/TEAMS`)·Actor alias(`TEAMS:{id}`)·Slack 노이즈 필터가 source
문자열 기반으로 자동 적용된다. 확인 두 가지만 한다: ① TEAMS 이벤트가 소비되는 스모크 테스트
② 소스 표시 라벨 — 유도 라벨은 `Teams`인데 카탈로그 명칭이 `MS Teams`이므로, 맞추려면
`_SOURCE_PREFIX_LABELS`에 한 줄 등록한다(표기 통일 결정에 따름).

## 7. 개인정보

- `actor.name`·`email`은 `docs/graph-schema.md` ActorAlias 규약대로 `pd_*`에만 저장된다. email은
  협업 툴 계정(Entra `mail`) 값이라 사용 가능 조건을 충족한다.
- Atlassian식 **개인정보 사용 보고 의무는 없다**(`docs/jira-personal-data-policy.md`의 보고 사이클은
  Teams에 해당 없음). 대신 admin consent가 테넌트 관리자 인지·통제 수단이 된다.
- 연동 해제 시 삭제는 공용 흐름(그래프 소스 삭제 → RDB·checkpoint 삭제)이 그대로 동작한다.

## 8. 문서 동반 갱신 (커넥터 PR에 포함)

- `docs/data-collection.md` — Teams 섹션(수집 대상·증분 전략·rate limit·트레이드오프).
- `docs/integration-abstraction.md` — Part B 표의 Teams 완료 표시.
- `services/backend/CLAUDE.md`(패키지 구조에 `teams`) · `services/pipeline-worker/CLAUDE.md`
  (`source.teams` 행, 라우팅 표, checkpoint 목록).
- `docs/graph-schema.md`·`docs/DB.md`는 변경 없음(새 노드·테이블 없음)을 확인만 한다.

## 9. 검증 계획

- 단위: backend `./gradlew test` / pipeline-worker `./gradlew test` / 프론트 `typecheck && build`.
- 선행 PR(§2): ✅ 완료 — Google Chat 작업 때 함께 처리됨(`IntegrationTokenClientTest`가
  REFRESHED/NOT_SUPPORTED/FAILED 3상태를, `GitHubWebhookServiceTest`가 "404 → 저장 자격증명으로
  진행(Slack·Discord 보존)"을 고정한다). Teams 착수 시 별도 조치 불필요.
- 실기동 시나리오(관리자 권한이 있는 테스트 테넌트 — §1-2에 따라 **비관리자 계정으로는 admin
  consent 단계에서 끝까지 진행 불가**): 연결 → admin consent → 팀 선택 → 초기 수집 → 그래프에 TEAMS
  Communication 확인 → PR 머지 웹훅으로 증분(1시간 뒤 토큰 갱신 경로 포함) → 오래된 스레드에 새 답글
  → 다음 증분에서 수집됨 확인 → 해제 시 TEAMS 노드만 삭제 확인.

## 구현 시 확인 (미확정)

1. **Teams 계열 throttling 상한 수치** — Learn 문서로 확정 후 `TeamsRateLimiter` 값 조정.
2. **private 채널의 delegated 접근 범위** — `/teams/{id}/channels`가 사용자 소속 private 채널을
   포함하는지 실측. 포함되면 그대로 수집(접근 가능 채널 전체 원칙), 아니면 표준 채널만.
3. **`$expand=replies` 페이지네이션 실측** — 답글 200개 초과 스레드에서 `replies@odata.nextLink`
   동작과 정렬(최신순) 확인. 이상하면 루트별 `/replies` 개별 호출로 대체(호출 수 증가 트레이드오프).
4. **편집·삭제 메시지의 목록 반영** — 소프트 삭제가 목록에 어떻게 남는지 실측(현 계획은 발행 제외.
   기존 노드 삭제 전파는 대화 아키타입 공통 과제로 미룸 — Slack도 동일하게 미지원).

## 참고 (Microsoft Learn, v1.0)

- List channel messages(권한·정렬·$expand): learn.microsoft.com/en-us/graph/api/channel-list-messages
- 권한 레퍼런스(admin consent 여부): learn.microsoft.com/en-us/graph/permissions-reference
- Refresh token(회전·수명): learn.microsoft.com/en-us/entra/identity-platform/refresh-tokens
- Throttling: learn.microsoft.com/en-us/graph/throttling-limits
