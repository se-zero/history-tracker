# Integration 추상화 계획 — 신규 데이터 소스 확장을 위한 리팩토링

신규 integration(이슈 트래커·대화·문서) 추가에 앞서, provider가 하드코딩된 계층을
아키타입 단위로 추상화하는 계획을 정리한다.

## 목표 소스

| 분류 | 추가 예정 | 현재 계획 없음 |
|------|----------|---------------|
| 프로젝트 관리 | Linear, Asana, monday.com, ClickUp | Azure DevOps, Trello, YouTrack, Shortcut |
| 대화 | MS Teams, Google Chat, Discord | Telegram, Mattermost, Zulip, 카카오워크, 네이버웍스, 잔디 |
| 문서 | Notion (doc 노드 우선, issue 노드 확장 가능) | |

## 1. 현재 진단 — provider 결합 지점

이미 절반은 추상화돼 있다. `NormalizedEvent`(nodeType 기준)가 소스 중립 계약이라
ai-engine은 거의 손댈 게 없고, 결합이 심한 곳은 pipeline-worker의 오케스트레이션 계층과
backend의 연동 관리 계층이다.

| 위치 | 상태 |
|------|------|
| ai-engine `graph/event_handler.py` | ✅ 이미 중립 — `nodeType`(ChangeSet·PullRequest·Issue·Communication)으로 분기, source는 속성일 뿐 |
| ai-engine 소스별 삭제·Actor alias | ✅ 이미 중립 — `source.upper()` 문자열 기반, 새 소스 추가 시 무변경 |
| RabbitMQ 라우팅 | ✅ `event.#` 바인딩이라 새 routing key 무변경 |
| pipeline-worker `source.{provider}` 패키지 | ✅ 이미 provider별 모듈 (RawService·Normalizer·RateLimiter) |
| pipeline-worker `pipeline/PipelineService` | ❌ `normalizeGitHub/Jira/Slack` 하드코딩, provider별 수집 루프가 한 클래스에 |
| pipeline-worker `collection/ProjectCollectionContext` | ❌ `github / Optional<jira> / Optional<slack>` 고정 필드 |
| pipeline-worker `collection/ProjectIntegrationService` | ❌ provider별 `resolveX`·credential 해석 메서드 나열 |
| pipeline-worker `checkpoint/CheckpointService` | ❌ `updateGitHubCommits`… provider별 메서드·필드 고정 |
| backend `integration/domain/Integration` | ❌ provider별 팩토리·getter·Jira 전용 상태머신이 엔티티에 집중 |
| backend `IntegrationService` / `IntegrationOAuthService` | ❌ connect·callback·revoke가 provider별 메서드 복제 (Slack/Jira 흐름이 거의 평행) |
| backend `V3__create_integrations.sql` | ❌ `CHECK (provider IN ('github','slack','jira'))` — provider 추가마다 마이그레이션 강제 |
| web-dashboard `sources/sourceCatalog.tsx` | ✅ 11종 카탈로그 이미 존재 / ❌ 카드·훅은 provider별 개별 배선 |

## 2. 설계 원칙 — "아키타입" 단위 추상화

추가할 8개 소스는 provider별 인터페이스가 아니라 **3개 아키타입**으로 떨어진다.
기존 3개가 각 아키타입의 레퍼런스 구현이다.

| 아키타입 | 레퍼런스 | 신규 | nodeType | 연결 플로우 |
|----------|---------|------|----------|------------|
| 이슈 트래커 | Jira | Linear, Asana, monday, ClickUp | `Issue` (기존) | OAuth → 대상 선택 (provider마다 1~4단) |
| 대화 | Slack | Teams, Google Chat, Discord | `Communication` (기존) | provider마다 갈린다 — Slack·Discord는 동의 즉시 확정, Teams는 1단 선택(§3-2 조사 결과) |
| 문서 | — | Notion | `Document` (**신규**) | OAuth → 즉시 확정 |

함의: **Notion만 ai-engine 신규 작업이 크고, 나머지 7개는 pipeline-worker 커넥터 +
backend OAuth 정의 + 프론트 카드만 추가하면 되는 구조**로 만드는 것이 추상화의 목표다.

주의: Jira/Slack(및 신규 소스)의 증분 수집이 GitHub PR 머지 웹훅에 앵커된 것은 **의도된
설계**다 — 추상화 과정에서 소스별 독립 웹훅으로 바꾸지 않고, 새 소스도 같은 웹훅 사이클에
편입시킨다.

## 3. 서비스별 추상화 설계

### 3-0. 계약 문서화 — `docs/normalized-event.md` (선행)

두 서비스 사이의 실질 계약(nodeType별 필수 properties, refs 키, source 표기 규칙)이
지금은 코드에 암묵적으로만 존재한다. 새 커넥터를 만들 때마다 GitHubNormalizer를
역공학하게 되므로 계약을 먼저 문서로 고정한다. 이후 모든 커넥터 PR의 체크리스트가 된다.

### 3-1. pipeline-worker — `SourceCollector` SPI (효과 최대)

`source.{provider}` 패키지가 구현하는 수집 SPI를 도입하고, 오케스트레이션 계층을
레지스트리 디스패처로 바꾼다.

```java
public interface SourceCollector {
    CollectionProvider provider();
    // DB integration row → 수집 요청 (credential 복호화 + external_ref 해석을 provider가 소유)
    Optional<RawFetchRequest> resolveFetchRequest(IntegrationRow row);
    // 증분 수집 1회: fetch → normalize → publish → checkpoint 갱신까지 provider가 소유
    int collect(String projectId, RawFetchRequest request);
}
```

- **PipelineService** → `Map<CollectionProvider, SourceCollector>`를 순회하는 얇은 디스패처.
  GitHub의 3단 루프, Jira의 토큰 페이징, Slack의 채널별 커서 같은 상이한 루프 구조는
  억지로 통일하지 않고 각 커넥터 내부로 이동만 한다.
- **CheckpointService** → provider별 메서드를 범용 API로:
  `loadCursors(projectId, provider) : Map<String, Instant>` /
  `updateCursor(projectId, provider, cursorKey, value)`.
  DB 스키마(`(project, provider, cursor_key)`)는 이미 범용이라 무변경.
  `ProjectCheckpointData`의 고정 필드는 제거한다.
- **ProjectCollectionContext** → `github`(웹훅 앵커, 필수) +
  `Map<CollectionProvider, RawFetchRequest>`(나머지).
- **ProjectIntegrationService** → credential/external_ref 해석을 각 커넥터의
  `resolveFetchRequest`로 이관하고, 안전망(잘못된 연동 스킵·로그)만 공용으로 남긴다.
- **CollectionTriggerService** → switch 제거, 레지스트리 조회.
- **RefsExtractor** → 패턴 레지스트리화. Linear 키는 `ABC-123` 형식이라 기존 Jira 정규식과
  동일 패턴에 걸리는 반면, Asana/ClickUp/Notion은 URL 기반 참조라 provider별 패턴 기여가
  필요하다. `refs.issueKeys`처럼 중립 키로 수렴한다.
  **→ 키 이름 중립화는 A6에서 완료** (ref 키 `jiraKey → issueKey` 계열, ai-engine과 동시 이행).
  URL 기반 참조 소스(Asana/ClickUp/Notion)를 위한 패턴 레지스트리화는 해당 커넥터 착수 시
  Part B에서 한다 — 지금은 정규식 하나뿐이라 등록 지점을 미리 만들 실익이 없다.

### 3-2. backend — 연동 프레임워크

- **DB 제약**: `chk_integrations_provider`의 열거형 CHECK를 폐기하고(유효성은 앱의 enum
  converter가 담당), credential 형태 제약만 범용 규칙으로 재정의하는 마이그레이션:
  `github → installation_id 필수`, `그 외 → encrypted_credential 필수`.
  이후 provider 추가 시 DB 마이그레이션 불필요.
- **OAuth 흐름 템플릿**: Slack/Jira의 authorize→callback 쌍이 이미 평행 구조이므로
  `OAuthIntegrationFlow` 전략 인터페이스(provider, authorize URL 조립, code 교환, revoke)로
  추출하고, 컨트롤러는 `/integrations/{provider}/authorize`·`/callback` 범용 라우트 하나로
  통합한다. `OAuthStateService`·콜백 302 리다이렉트·에러 코드 규약은 이미 공용이라 그대로
  재사용한다.
  **→ `OAuthConnectFlow`로 구현.** 저장 정책(409 선검사 → 암호화 → 저장 → 수집 트리거)은 전략이 아니라
  `IntegrationService.connectOAuth`가 provider 공통으로 소유한다 — 전략은 `exchangeCode`가
  `OAuthConnection`(자격증명 평문 + 수집 대상 참조)을 돌려주는 데서 끝난다. 처음에는 전략이
  `IntegrationService`의 provider별 메서드(`connectSlackWorkspace`·`connectJiraSite`)를 호출하는 형태였는데,
  그러면 커넥터 담당자가 공용 서비스에 자기 메서드를 계속 덧붙여야 해서 뒤집었다.
  덕분에 세 SPI가 모두 leaf가 됐다(전략 → `IntegrationService` 의존이 사라졌다).
- **2단계 선택(pending) 일반화**: Jira 전용인 `pending_project` 상태머신을 provider 중립
  `pending_selection`으로 승격 — 신규 이슈 트래커 4종이 전부 이 패턴(동의 → 워크스페이스/
  프로젝트 선택)을 쓴다. 범용 엔드포인트:
  `GET /integrations/{provider}/options`(선택지 조회, provider가 payload 형태 정의) +
  `POST /integrations/{provider}/complete`.
  **→ Part A에서 처리한다.** 커넥터를 팀원이 분담하는 이상, 이걸 첫 커넥터 담당자에게 넘기면
  그 사람이 나머지 3명의 작업 전제를 설계하게 되어 이슈 트래커 4종이 직렬이 된다.
  단, 인터페이스는 Jira 하나가 아니라 **아래 조사 결과를 근거로** 설계한다.

#### 대상 provider의 선택 모델 (조사 결과)

| provider | 선택 단계 | 비고 |
|----------|----------|------|
| Jira (기존) | 2단 — site(cloudId) → project | |
| Linear | **1단** — team | 워크스페이스는 OAuth 토큰에 암시된다. 이슈는 team 기반 |
| Asana | 2단 — workspace → project | project는 단일 workspace·team에 속한다 |
| monday.com | 2단 — workspace → board | 모든 board는 workspace 안에 있다 |
| ClickUp | **2~4단** — workspace(team) → space → *folder(선택)* → list | folder 없이 space 직속 list 존재 |

설계에 그대로 반영해야 하는 세 가지.

1. **단계 수가 1~4로 다르다** — 고정 2단(`sites` + `projects`) 인터페이스는 Linear에서 과하고
   ClickUp에서 모자란다.
2. **중간 단계가 선택적일 수 있다** — ClickUp의 folder는 건너뛸 수 있다(folderless list).
   "N단 고정"으로도 부족하고 단계 건너뛰기를 지원해야 한다.
3. **모든 단계가 앞 선택에 의존한다** — 자식 목록 조회에 부모 id가 필요하므로, 단계별 옵션 조회는
   "지금까지의 선택"을 입력으로 받아야 한다.

여기서 나오는 결론: 어느 깊이까지 고르게 할지는 API가 아니라 **제품 결정**이다(ClickUp을 space까지만
고르게 할지 list까지 고르게 할지). 따라서 백엔드는 단계 스키마를 고정하지 말고 **provider가 자기 단계를
선언**하게 하고, 프론트는 그 선언을 그대로 렌더링해야 한다.

대화 3종(Teams·Google Chat·Discord)도 1단 선택(팀/스페이스/길드)이 필요해 보이지만 권위 있는 문서로
확인하지 못했다 — 같은 메커니즘을 재사용할 수 있는지는 해당 커넥터 착수 전에 확인한다.
**→ Teams·Discord 확인 완료.** Teams는 `/me/joinedTeams` 기반 **1단(team) 선택**으로 A4 메커니즘을
그대로 재사용한다(`docs/teams-integration.md`). Discord는 반대로 **선택 단계가 없다** — 자기 동의
화면에서 서버를 고르게 하고 콜백/토큰 응답으로 길드를 알려주므로 Slack형이다
(`docs/discord-integration.md`). 즉 대화 3종이 한 모양이 아니다.
**→ Google Chat도 확인 완료.** `spaces.list`(`spaceType = "SPACE"`) 기반 **1단(space) 선택**으로
Teams와 같은 모양이다(`docs/google-chat-integration.md`). 정리하면 대화 3종은 선택 없음(Discord) /
1단(Teams·Google Chat)으로 갈리며, **대화형에서 A4 메커니즘을 실제로 검증하는 것은 둘 중 먼저
착수하는 쪽**이다.
Slack은 접근 가능한 전체 채널을 자동 수집해 선택 단계가 없다.
- **토큰 갱신 일반화**: `JiraTokenService`를 provider별 갱신 정책(만료 여부·refresh 회전
  여부)을 선언하는 형태로 확장. Teams/Google Chat은 Jira처럼 만료+갱신형,
  Notion/Discord는 비만료형이라 정책 선언만으로 흡수된다. 내부 API도
  `/internal/integrations/{projectId}/{provider}/token`으로 범용화한다.
  **→ 전용 SPI `AccessTokenRefresher`로 구현.** 비만료형은 구현하지 않으면 그만이고, 갱신 수단이
  없는 provider는 조용한 204 대신 404를 받는다.
  처음에는 `ProviderCredentialLifecycle.ensureFreshAccessToken`(기본 no-op)이었는데, 그러면 404 판정이
  "갱신을 지원하는가"가 아니라 "자격증명 빈이 있는가"가 돼 **폐기만 있고 갱신은 없는 Slack이 조용한 204를
  받았다** — 호출부는 갱신됐다고 믿고 만료된 토큰으로 수집한다. 기본 no-op은 "지원하지 않음"과
  "아무 일도 필요 없음"을 호출부가 구분할 수 없게 만들어서, 능력은 빈 등록 여부로 표현하도록 분리했다.
- **Integration 엔티티 다이어트**: provider별 팩토리·typed getter를 각 provider 패키지의
  external_ref 뷰 클래스로 이동, 엔티티는 범용 상태(externalRef Map + pending 플래그)만
  유지한다.
  **→ A5에서 완료.** 팩토리는 `Integration.oauth(...)`·`Integration.pendingSelection(...)`으로,
  Jira typed getter는 범용 `selectionValue(key)`로 대체했다 — 엔티티에 provider 이름이 남지 않는다.
  키 상수는 `JiraSelectionFlow`가 소유한다.
- **revoke**: `IntegrationService.revokeProviderAccess`의 switch를 전략의 `revoke()`로 흡수한다.
  **→ `ProviderCredentialLifecycle.revoke`로 구현.**

### 3-3. ai-engine — 최소 개입 + 한 가지 결정

- 이슈·대화 아키타입은 **무변경**이 원칙 (소스 문자열이 열려 있음을 스모크 테스트로만 확인).
- **`jira_key` 중립화 → A6에서 "개명"으로 실행 완료**: `Issue.jira_key → issue_key`,
  `PullRequest.jira_keys → issue_keys`, refs `jiraKey(s)`/`parentJiraKey` → `issueKey(s)`/`parentIssueKey`.
  전환 장치는 인플라이트 이벤트용 하나뿐이다 — 옛 키 이벤트는 `event_handler._normalize_legacy_keys`가
  진입점에서 정규화한다(브로커 잔여분·DLQ replay 호환). 옛 키가 더는 관측되지 않으면 제거해도 된다.
  **저장 데이터 이행 장치는 두지 않는다** — 개발 단계라 보존할 그래프가 없어, 유지하려면 필요한
  배치 처리·옛/새 키 중복 노드 검증·테스트보다 제거가 효율적이라 판단했다. 옛 키로 저장된 노드가
  남은 환경은 그래프를 새로 구축한다(`DELETE /graph/projects/{id}` 후 재수집).
  `tools/queries`·에이전트 프롬프트·`docs/tools.md`·`docs/graph-schema.md`·`docs/normalized-event.md`
  동반 수정 완료.
- **Notion `Document` 노드는 별도 설계 단계**: 스키마 제약, upsert+임베딩, Layer 2(문서
  본문의 이슈 키/PR 참조), Layer 4 REFERENCE 대상 편입, tool 정의 추가, 성좌 뷰 렌더링까지
  걸리는 실제 신규 기능이다. 추상화 PR에 섞지 않는다.
  communication·issue 노드와의 연결 방식은 설계 단계에서 확정한다(추후 변경 가능).
- Slack 노이즈 필터(`slack_filter` 계열)는 이미 모든 Communication에 적용되므로
  Teams/Discord도 자동으로 통과한다 — 이름만 나중에 `communication_filter`로 정리 후보.

### 3-4. web-dashboard — 연결 플로우 디스크립터 (A7에서 구현 완료)

구현 결과, 플로우 종류를 프론트가 열거하는 대신 **backend의 단계 선언을 그대로 렌더**하는
쪽으로 단순화했다 — `oauth`(Slack형)와 `oauth-select`(Jira형)는 결국 "선언된 단계 수 0 vs N"의
차이일 뿐이라 카드 하나(`OAuthSourceCard`)가 둘 다 감당한다. GitHub(설치 기반)만 전용 카드로 남는다.

- `hooks/useSelectionFlow.ts` — `/{provider}/selection/steps·options` 구독 + 확정 mutation.
  단계 수가 provider마다 달라(1~4단) `useQueries` 배열형으로 후보를 구독하고, 선택적(optional)
  단계는 건너뛰어도 다음 단계가 열린다(reachable 판정은 필수 단계만 본다).
- `components/sources/OAuthSourceCard.tsx` — pending이면 선언된 단계를 순서대로 렌더:
  앞 단계를 골라야 다음 단계가 열리고, 앞 단계를 바꾸면 뒤 단계 선택은 버린다.
  필수 단계 후보가 1개면 자동 선택(Atlassian resource-level grant형). 확정은 전 단계 일괄 제출.
- `sourceCatalog` 항목을 `status`로 갈리는 판별 유니온으로 — `"wired"`라고 선언하면
  `connect`(연결 방식)·`deletedData`(해제 다이얼로그 문구)를 반드시 함께 적어야 컴파일이 통과한다.
  **신규 provider의 프론트 작업은 브랜드 마크 + 카탈로그 한 줄이 전부다.**
  처음에는 두 필드가 optional이었는데, 그러면 하나만 채운 반쪽 배선이 **무증상으로 통과한다**
  (연결 버튼이 조용히 no-op이거나, 해제 다이얼로그가 "수집한 데이터" 같은 뭉뚱그린 폴백 문구를 띄운다).
  provider별 카드를 공용 하나로 합치면서 "카드가 없으면 안 붙는다"는 컴파일 안전망이 사라진 자리라,
  타입으로 되살렸다 — backend `IntegrationResponse.displayName`의 exhaustive switch와 같은 역할이다.
- `useIntegrationAuthorize`는 provider를 mutate 인자로 받는 단일 훅으로 통합.

## 4. 진행 순서

작업은 성격이 다른 두 덩어리다. **Part A는 커넥터를 하나도 추가하지 않는 전체 소스 추상화**이고,
**Part B는 커넥터별 구현**이다. Part B는 팀원이 나눠 맡으므로, Part A가 끝나기 전에는 시작하지 않는다.

### Part A — 전체 소스 추상화 (커넥터 0개)

**완료 판정 기준: 커넥터 담당자가 공용 코드를 고치지 않고 자기 provider 파일만 추가하면 되는 상태.**
이 기준을 넘지 못하면 Part B의 병렬 분담이 성립하지 않는다 — 한 사람의 설계가 나머지의 전제를 바꾼다.

| 순서 | 내용 | 검증 |
|------|------|------|
| ~~A1~~ ✅ | `docs/normalized-event.md` 수집 계약 문서화 | 완료 |
| ~~A2~~ ✅ | pipeline-worker `SourceCollector` SPI (**동작 불변**) | 완료 — `./gradlew test` 192개 그린 |
| ~~A3~~ ✅ | backend OAuth·자격증명 전략 2종 + DB 제약 마이그레이션 (**동작 불변**) | 완료 — `./gradlew test` 509개 그린(Testcontainers 스키마 검증 포함) |
| ~~A4~~ ✅ | **다단 선택(pending_selection) 일반화** — provider가 자기 단계를 선언, 백엔드·프론트는 스키마 고정 없이 구동. Jira를 이 메커니즘 위로 이전 | 완료 — 512개 그린. 선택적 중간 단계(ClickUp folder형) 테스트로 고정 |
| ~~A5~~ ✅ | Integration 엔티티 typed getter 중립화 (A4에 묶임) | 완료 — 엔티티에 provider 이름이 남지 않는다 |
| ~~A6~~ ✅ | ai-engine `issue_key`·`refs` 키 중립화. 저장 데이터 이행 장치는 두지 않는다(개발 단계 — 옛 키가 남은 그래프는 재구축) | 완료 — `pytest` 그린 + pipeline 그린. eval(docs/measurement.md) 회귀는 라이브 인프라·OpenAI 키 필요라 배포 후 1회 권장 |
| ~~A7~~ ✅ | web-dashboard 연결 플로우 디스크립터화 (A4의 단계 선언을 그대로 렌더링) — Jira 선택 화면 복구. JiraCard·SlackCard가 범용 `OAuthSourceCard` 하나로 통합 | 완료 — `npm run typecheck && npm run build` 그린 |

A4가 이 단계의 핵심이다 — 이슈 트래커 4종이 **전부** 이 경로를 지나므로, 여기가 provider별로 갈리면
담당자 4명이 같은 자리를 각자 고치게 된다.

#### ~~A8~~ ✅ (추가 발견, 2026-08-08 발견 · 같은 날 완료) — `ProviderCredentialLifecycle.revoke`가 external_ref를 못 받았다

Part A의 완료 판정 기준은 "커넥터 담당자가 공용 코드를 고치지 않고 자기 provider 파일만 추가하면
되는 상태"인데, Discord 조사에서 이 기준을 깨는 구멍이 하나 나왔다.

시그니처가 `revoke(byte[] encryptedCredential)`로 자격증명만 받았다. Slack(`auth.revoke`)과
Jira(refresh token 폐기)는 이걸로 충분했지만, **Discord의 의미 있는 폐기는 "봇이 서버를 떠나는 것"**
(`DELETE /users/@me/guilds/{guild_id}`)이라 `external_ref.guild_id`가 필요하다. 즉 Discord는 폐기에
수집 대상 참조가 필요한 첫 provider다. 그냥 두면 연동을 해제해도 **봇이 사용자 서버에 남는다.**

**→ 완료.** `revoke(byte[] encryptedCredential, Map<String, Object> externalRef)`로 넓혔다. 기존
두 구현체(`SlackCredentialLifecycle`·`JiraCredentialLifecycle`)는 새 인자를 무시하도록 수정했고,
호출부(`IntegrationService.revokeProviderAccess`)는 이미 들고 있는 연동 행에서 `integration.getExternalRef()`를
그대로 전달한다 — 추가 조회가 필요 없었다. `./gradlew test` 전체 그린(Testcontainers 스키마 검증 포함).
Discord 커넥터 PR과 분리한 선행 PR로 처리했다.

#### ~~A9~~ ✅ (2026-08-09 발견 · 같은 날 완료) — `checkpoints.provider`에 열거형 CHECK가 남아 있었다

A8과 **정확히 같은 성격의 구멍**이며, 이번에는 실기동에서 드러났다. V12가 `integrations.provider`의
열거형 CHECK를 없앴지만, V5가 만든 `checkpoints` 테이블의 제약은 그대로다.

```sql
CONSTRAINT chk_checkpoints_provider CHECK (provider IN ('github','jira','slack'))
```

그래서 Discord 수집은 **fetch·normalize·publish까지 정상으로 끝난 뒤 마지막 checkpoint 쓰기에서
터진다.**

```
ERROR: new row for relation "checkpoints" violates check constraint "chk_checkpoints_provider"
  Detail: Failing row contains (0c626ddc-…, discord, discord_messages, …)
```

증상이 고약한 이유는 실패 지점이 맨 끝이라서다. 이벤트는 이미 발행됐으므로 그래프에는 데이터가
들어가지만 **커서가 영원히 전진하지 못해**, 수집을 돌릴 때마다 같은 구간을 다시 긁고 같은 자리에서
다시 실패한다(재발행이 멱등이라 데이터 사고는 아니다). 로그를 보지 않으면 "연동은 됐는데 왜 계속
같은 것만 들어오지?"로 보인다.

**Part A의 완료 판정 기준("공용 코드를 고치지 않고 자기 provider 파일만 추가")을 깨는 항목**이며,
Discord뿐 아니라 **Linear·Google Chat·Teams 등 앞으로의 모든 커넥터가 같은 벽에 부딪힌다.**

**→ 완료.** `V13__drop_checkpoints_provider_constraint.sql`로 `chk_checkpoints_provider`를 제거했다
(V12가 `integrations`에 적용한 논리와 동일 — 유효성은 `CollectionProvider` enum이 보증).
`PipelineSharedSchemaTest.checkpointProviderRejectsUnexpectedValue`(옛 CHECK 거부를 고정하던 테스트)를
`checkpointAcceptsNewProviderValueWithoutSchemaMigration`으로 교체해 반대 방향(새 provider 값도
저장 가능)을 고정했다 — V12 때 `integrations`만 보고 같은 테이블 계열을 함께 훑지 않아 생긴 누락이었다.
`./gradlew test` 그린. 배포된 DB에도 반영해 Discord 재수집으로 checkpoint 정상 갱신을 실측 확인했다
(`docs/discord-integration.md` §9 참고).

### Part B — 커넥터별 구현 (팀원 분담, 커넥터당 1 PR)

Part A가 끝났다면 커넥터끼리 서로 독립이므로 순서 제약 없이 병렬로 진행한다.
**커넥터 1개를 끝내는 전체 순서는 아래 「커넥터 엔드투엔드 체크리스트」다** —
`docs/normalized-event.md`의 「새 커넥터 체크리스트」는 그중 *수집 계약* 부분만 다루므로
그것만 따라가면 연동 UI 없이 수집기만 만들고 끝난다.

| 아키타입 | 대상 | 비고 |
|----------|------|------|
| 이슈 트래커 | Linear · Asana · monday.com · ClickUp | `Issue` 노드 재사용, ai-engine 무변경 |
| 대화 | **Discord**(코드 작업 완료 ✅ — 연결·수집(A9 수정 후 checkpoint 갱신 실측 확인) 전부 정상) · MS Teams(계획 완료, 라이선스 대기) · **Google Chat**(코드 작업 완료 ✅ — backend·pipeline-worker·web-dashboard, 선행 PR 2건(webhook 토큰 확보 일반화·A9) 포함. `docs/google-chat-integration.md`. §1-0 Workspace 계정 게이트 실측·실기동은 미착수) | `Communication` 노드 재사용, ai-engine 무변경. Slack 노이즈 필터가 자동 적용된다 |
| 문서 | Notion | **예외** — `Document` 노드 신규 설계가 선행한다. ai-engine 작업이 크므로 마지막 |

Linear를 이슈 트래커 1호로 권한다: 선택이 1단(team)이라 A4 메커니즘의 최소 경로를 먼저 태워 보고,
이후 2단(Asana·monday)·가변단(ClickUp)이 같은 메커니즘에 얹히는지 확인하는 순서가 된다.

**대화 아키타입 1호는 Discord다** (2026-08-08 결정). 원래 MS Teams 자리였고 빈 `teams` 디렉터리도
그래서 만들어 뒀지만, 조사 결과 Teams Graph API는 **유료 조직 테넌트 라이선스 + 테넌트 관리자 동의**를
개발자와 최종 사용자 양쪽에 요구한다(개인 계정은 우회 불가 — `docs/teams-integration.md` §1-0의 실측).
아키타입이 성립하는지를 증명하는 데 그 비용을 먼저 치를 이유가 없다. Discord는 서버 생성·봇 등록이
무료이고 관리자 동의 절차가 없다. Teams는 계획 문서가 이미 완성돼 있으므로 라이선스가 확보되는 대로
2호로 착수한다.

**단, Discord가 검증하는 범위는 처음 생각과 다르다**(`docs/discord-integration.md` 조사 결과).
Discord는 자기 동의 화면에서 서버를 고르게 해 **선택 단계가 아예 없으므로**(Slack형),
"대화형에서도 A4 다단 선택이 통하는가"는 Discord로 확인되지 않는다 — 그 검증은 Teams(1단 team)나
Google Chat 몫으로 남는다. Discord가 실제로 검증하는 것은 ① 대화 아키타입이 Slack 외 소스로도
성립하는가 ② **비만료형 provider의 404 경로**(봇 토큰은 만료되지 않아 `AccessTokenRefresher`를
만들지 않는다 — Slack의 조용한 204 사건 이후 만든 안전망을 두 번째 provider로 확인) ③ 앱 수준 봇 +
프로젝트별 설치 대상이라는 GitHub App형 자격증명 모델이 OAuth 프레임워크에 얹히는가, 셋이다.
③에서 공용 SPI의 구멍이 하나 드러났다 — 위 Part A의 「A8」 참고.

각 단계마다 대응 문서(data-collection.md, DB.md, graph-schema.md, 각 CLAUDE.md) 동반 갱신이 필요하다.

### 커넥터 엔드투엔드 체크리스트

커넥터 1개 = 1 PR. **연결(backend) → 수집(pipeline-worker) → 화면(web-dashboard)** 순으로 하면
각 단계를 실제로 눌러 보며 다음 단계로 넘어갈 수 있다. 상세 규칙은 각 항목이 가리키는 문서를 본다.

**0. 사전 준비 — 외부 앱 등록**

- [ ] provider 개발자 콘솔에서 OAuth 앱 생성, redirect URI를 `{BASE}/api/v1/integrations/{provider}/callback`으로 등록.
      **경로의 `{provider}`는 소문자 kebab**이며 이후 바꿀 수 없다(등록된 URI가 깨진다).
- [ ] 필요한 scope 확정 — 최소 권한만. 개인정보(이름·이메일) scope는 `docs/graph-schema.md`의 ActorAlias 규약과
      `docs/jira-personal-data-policy.md`(보고 의무가 있는 provider라면)를 먼저 확인한다.
- [ ] `infra/docker/docker-compose.yml`의 backend 환경변수에 `{PROVIDER}_CLIENT_ID`·`_CLIENT_SECRET`·
      `_REDIRECT_URI` 추가(`ATLASSIAN_*` 패턴). 실제 값은 `.env`(gitignore)에.

**1. backend — 연결 (`services/backend/CLAUDE.md` 「provider 전략」·「다단 선택」)**

- [ ] `IntegrationProvider` enum에 상수 추가 (`LINEAR("linear", "Linear")`).
      **DB 마이그레이션은 불필요** — V12에서 provider CHECK 제약을 제거했다.
- [ ] `{provider}/AtlassianProperties`형 `@ConfigurationProperties` 레코드 + `application.yaml` 블록 추가.
- [ ] `OAuthConnectFlow` 구현 — 동의 URL 조립, `exchangeCode`가 `OAuthConnection`(자격증명 평문 +
      수집 대상 참조)을 돌려준다. 참조의 키 이름은 provider가 정하고 pipeline-worker가 같은 키를 읽는다(2번과 합의).
      선택 단계가 있으면 `OAuthConnection.pendingSelection(...)`으로 자격증명만 넘긴다.
      **저장·암호화·수집 트리거는 `IntegrationService.connectOAuth`가 공통으로 하므로 손대지 않는다** —
      고쳐야 한다면 추상화가 새는 것이므로 먼저 상의한다.
- [ ] `ProviderCredentialLifecycle` 구현 — 연동 해제 시 원격 폐기 수단이 있는 provider만. 없으면 만들지 않는다.
- [ ] `AccessTokenRefresher` 구현 — **만료되는 토큰을 쓰는 provider만**(Teams·Google Chat형).
      비만료형(Notion·Discord형)은 만들지 않는다 — 빈이 없으면 내부 토큰 API가 404로 답해
      호출부가 "갱신 못 함"을 알 수 있다. **폐기가 있다고 이걸 함께 만들면 안 된다**(Slack이 조용한 204를 받던 원인).
- [ ] `IntegrationSelectionFlow` 구현 — 선택 단계가 있는 provider만.
      `SelectionStep.key`가 **그대로 `external_ref` 키**가 되고 pipeline-worker가 같은 키를 읽는다(2번과 합의).
      선택이 없는 provider(동의 즉시 확정, Slack형)는 이 SPI를 만들지 않는다.
- [ ] `IntegrationResponse.displayName`의 switch에 case 추가 — 연동 행에 무엇을 보여줄지 정한다.
      **선택 단계가 여럿이면 상위 단계도 함께 잇는다**(Jira는 `사이트 / 프로젝트`) — 말단 이름만
      실으면 워크스페이스를 여러 개 쓰는 조직에서 어느 쪽 것인지 화면에서 구분되지 않는다.
      (exhaustive switch라 **추가하지 않으면 컴파일이 깨진다** — 의도된 안전망이다.)
- [ ] 검증: `./gradlew test`

**2. pipeline-worker — 수집 (`services/pipeline-worker/CLAUDE.md` 「SourceCollector SPI」)**

- [ ] `CollectionProvider` enum에 상수 추가.
- [ ] routing key는 **설정하지 않는다** — `EventPublisher`가 `source`에서 유도한다
      (`GOOGLE_CHAT` → `event.google_chat`). 큐 바인딩이 `event.#`라 브로커 설정도 불변이다.
- [ ] `source/{provider}` 패키지에 `SourceCollector` 구현(`@Service`) — fetch·normalize·publish·checkpoint.
      `resolveFetchRequest`의 실패 신호 2종(`Optional.empty()` vs 예외)을 구분해 반환한다.
- [ ] 발행 계약 준수 — **`docs/normalized-event.md`의 「새 커넥터 체크리스트」 9항목이 여기 해당한다.**
      특히 `occurredAt`(checkpoint 정확도), `closed_at` 3-상태 규약, 담당자 해제 규약, `actor.id` 안정성.
- [ ] `PipelineService`·`CollectionTriggerService`·`ProjectIntegrationService`·`CheckpointService`는
      **건드리지 않는다.** 고쳐야 한다면 추상화가 새는 것이므로 먼저 상의한다.
- [ ] 검증: `./gradlew test`

**3. web-dashboard — 화면 (`clients/web-dashboard/CLAUDE.md`)**

- [ ] `components/sources/sourceCatalog.tsx`의 해당 항목을 `status: "planned"` → `"wired"`로 바꾸고
      `connect`("oauth")와 `deletedData`(해제 시 무엇이 지워지는지)를 채운다.
      **11종의 브랜드 마크와 카탈로그 항목은 이미 있다** — 보통 이 한 줄이 전부다.
      `"wired"`인데 두 필드가 없으면 **컴파일이 깨진다**(의도된 안전망 — 반쪽 배선은 화면에서 조용히 실패한다).
- [ ] 연동 행·선택 폼·타일·해제 다이얼로그는 `OAuthSourceCard`가 backend 단계 선언으로 렌더하므로
      **provider별 컴포넌트를 만들지 않는다.**
- [ ] **`pages/PrivacyPage.tsx` 고지 추가 — 배포 기준이다.** 카탈로그와 달리 자동으로 채워지지 않고
      컴파일도 막아주지 않아, 빠뜨리면 **고지 없이 개인정보를 수집하는 상태로 배포된다**
      (Discord·Google Chat이 실제로 이렇게 새어 이 항목이 생겼다). 세 곳을 함께 고친다 —
      제1조 「연동 자격증명」 행(저장하는 토큰 종류가 provider마다 다르다),
      제1조 「연동으로 수집되는 기록」 목록, 제2조 `LegalSourceBlock`(요청 권한·수집하는 정보·
      이용 목적·삭제·쓰기 권한). 이름·이메일을 수집하면 **어느 API로 얻는지까지 밝힌다**
      (예: Google Chat은 Chat API가 이름을 주지 않아 People API를 따로 호출한다).
      해제 시 원격에서 일어나는 일도 적는다(예: Discord는 봇이 서버에서 나간다).
- [ ] 검증: `npm run typecheck && npm run build`

**4. ai-engine — 기존 아키타입이면 무변경**

- [ ] `Issue`/`Communication`으로 정규화되면 코드 변경 없음. 소스별 삭제·Actor alias·Slack 노이즈 필터는
      `source` 문자열 기반이라 자동 적용된다.
- [ ] Notion(`Document`)만 예외 — 신규 노드 설계가 선행한다.
- [ ] 검증(변경했다면): `python -m pytest`

**5. 문서 동반 갱신**

- [ ] `docs/data-collection.md` — 이 provider의 수집·checkpoint 전략.
- [ ] `docs/graph-schema.md` — 새 노드·엣지를 추가했다면.
- [ ] 위 표(Part B)의 해당 행에 완료 표시.

### 공용 코드에 의도적으로 남긴 provider 분기

`IntegrationResponse.displayName`의 switch **하나뿐**이며, 의도적으로 남긴 것이다. exhaustive switch라
새 provider가 표시 이름을 정하지 않으면 컴파일이 깨져서, 화면에 빈 이름이 나가는 걸 막는다.

프론트의 `sourceCatalog` 판별 유니온도 같은 성격의 안전망이다(분기가 아니라 타입 제약이라 provider별
코드가 늘지는 않는다). 원칙은 하나다 — **provider별로 반드시 정해야 하는 값은 빠뜨렸을 때 화면에서
조용히 이상해지는 대신 컴파일에서 깨지게 한다.**

그 밖의 provider 분기는 Part A에서 모두 제거했다. `EventPublisher`의 source switch와
`routing-key-{provider}` 설정 3줄도 없앴고(routing key를 `source`에서 유도), 새 소스가
발행기를 고치지 않고 라우팅되는지는 `EventPublisherTest`가 고정한다.
ai-engine의 소스 표시 라벨(`graph/overview.py`)도 대문자 snake에서 유도하므로 등록이 필요 없다 —
`GitHub`·`ClickUp`처럼 **유도로 표기가 틀어지는 이름만** `_SOURCE_PREFIX_LABELS`에 넣는다.

## 5. 미리 정할 것

1. ~~**`jira_key` → `issue_key` 개명 여부**~~ — "개명"으로 결정, A6에서 실행 완료. (§3-3 참고)
2. ~~**provider ID 표기 통일**~~ — 확정. 계층별 관례를 그대로 따른다: RDB/HTTP 경로는 소문자 kebab
   (`linear`·`google-chat`), 그래프 source·alias 접두는 대문자 snake(`LINEAR`·`GOOGLE_CHAT`),
   routing key는 소문자 snake(`event.google_chat`), Java 패키지는 구분자 없음(`googlechat`).
   단일 출처는 `docs/normalized-event.md`의 「source · 표기 규칙」이며,
   두 단어 케이스의 근거·전 계층 대조표는 `docs/google-chat-integration.md` §10에 있다.
