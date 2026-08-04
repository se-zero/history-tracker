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
| 이슈 트래커 | Jira | Linear, Asana, monday, ClickUp | `Issue` (기존) | OAuth → 워크스페이스/프로젝트 선택 (2단계) |
| 대화 | Slack | Teams, Google Chat, Discord | `Communication` (기존) | OAuth/봇 설치 → 즉시 확정 |
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
  **→ 4단계로 미룬다.** ref 키 이름(`jiraKey` → `issueKey`)은 ai-engine이 소비하는 계약이라
  pipeline-worker 안에서 "동작 불변"으로 끝나지 않는다. `jira_key` → `issue_key` 중립화와
  같은 PR에서 함께 옮기는 편이 마이그레이션이 한 번으로 끝난다.

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
- **2단계 선택(pending) 일반화**: Jira 전용인 `pending_project` 상태머신을 provider 중립
  `pending_selection`으로 승격 — 신규 이슈 트래커 4종이 전부 이 패턴(동의 → 워크스페이스/
  프로젝트 선택)을 쓴다. 범용 엔드포인트:
  `GET /integrations/{provider}/options`(선택지 조회, provider가 payload 형태 정의) +
  `POST /integrations/{provider}/complete`.
- **토큰 갱신 일반화**: `JiraTokenService`를 provider별 갱신 정책(만료 여부·refresh 회전
  여부)을 선언하는 `OAuthTokenService`로 확장. Teams/Google Chat은 Jira처럼 만료+갱신형,
  Notion/Discord는 비만료형이라 정책 선언만으로 흡수된다. 내부 API도
  `/internal/integrations/{projectId}/{provider}/token`으로 범용화한다.
- **Integration 엔티티 다이어트**: provider별 팩토리·typed getter를 각 provider 패키지의
  external_ref 뷰 클래스로 이동, 엔티티는 범용 상태(externalRef Map + pending 플래그)만
  유지한다.
- **revoke**: `IntegrationService.revokeProviderAccess`의 switch를 flow 전략의 `revoke()`로
  흡수한다.

### 3-3. ai-engine — 최소 개입 + 한 가지 결정

- 이슈·대화 아키타입은 **무변경**이 원칙 (소스 문자열이 열려 있음을 스모크 테스트로만 확인).
- **결정 필요 — `jira_key` 중립화**: Issue 노드의 유니크 키 속성명이 `jira_key`인데 Linear
  이슈가 들어오면 어색해진다. 권장: 커넥터 추가 전에 `issue_key`로 개명. 전환 순서는
  ① ai-engine이 양쪽 키 수용 → ② pipeline이 새 키 발행 → ③ 기존 admin 마이그레이션
  인프라(`graph/maintenance.py`)로 저장 데이터 개명 → ④ 구 키 제거.
  `tools/queries`·에이전트 프롬프트·`docs/tools.md`·`docs/graph-schema.md` 동반 수정.
  (대안: "외부 이슈 키"로 의미만 재정의하고 이름 유지 — 마이그레이션 0이지만 영구 부채)
- **Notion `Document` 노드는 별도 설계 단계**: 스키마 제약, upsert+임베딩, Layer 2(문서
  본문의 이슈 키/PR 참조), Layer 4 REFERENCE 대상 편입, tool 정의 추가, 성좌 뷰 렌더링까지
  걸리는 실제 신규 기능이다. 추상화 PR에 섞지 않는다.
  communication·issue 노드와의 연결 방식은 설계 단계에서 확정한다(추후 변경 가능).
- Slack 노이즈 필터(`slack_filter` 계열)는 이미 모든 Communication에 적용되므로
  Teams/Discord도 자동으로 통과한다 — 이름만 나중에 `communication_filter`로 정리 후보.

### 3-4. web-dashboard — 연결 플로우 디스크립터

카탈로그는 이미 있으니, provider별 카드 대신 **플로우 종류**로 컴포넌트를 나눈다:
`github-install`(설치 선택) / `oauth`(리다이렉트 즉시 확정 — Slack형) /
`oauth-select`(리다이렉트 후 선택 — Jira형). `sourceCatalog` 항목에 `connectFlow` 필드를
추가하면 신규 provider는 카탈로그 한 줄 + (필요 시) 선택 스텝 커스텀만으로 연결된다.
`useIntegrationOAuth`·`DisconnectIntegration`은 이미 provider 파라미터화에 가까워 소폭 수정.

## 4. 진행 순서 (PR 단위)

| 순서 | 내용 | 검증 |
|------|------|------|
| ~~1~~ ✅ | `docs/normalized-event.md` 계약 문서화 | 완료 |
| ~~2~~ ✅ | pipeline-worker `SourceCollector` SPI 리팩토링 (**동작 불변**) | 완료 — `./gradlew test` 192개 그린 |
| 3 | backend 연동 프레임워크 + DB 제약 마이그레이션 (**동작 불변**) | `./gradlew test` + 기존 3종 연결/해제 수동 확인 |
| 4 | ai-engine `issue_key` 중립화 + 마이그레이션 | `pytest` + eval(docs/measurement.md) 회귀 확인 |
| 5 | web-dashboard 플로우 디스크립터화 | `npm run typecheck && npm run build` |
| 6 | **1호 신규 커넥터: Linear** — 추상화 실전 검증 | 아키타입별 첫 구현이 SPI의 리트머스 |
| 7+ | Teams(대화 1호) → 나머지 (Asana·monday·ClickUp·Google Chat·Discord) | 커넥터당 1 PR |
| 마지막 | Notion — `Document` 노드 설계 문서 먼저, 구현은 그다음 | 별도 설계 리뷰 |

1호를 Linear로 권하는 이유: Jira와 구조가 가장 가깝고(2단계 선택 + updated 커서 증분 +
동일한 `ABC-123` 키 형식), 추상화한 표면(SPI·pending 일반화·issue_key)을 전부 한 번에
검증한다. backend·pipeline-worker에 이미 만들어 둔 빈 `teams` 디렉터리는 대화 아키타입
1호 자리로 그대로 둔다.

각 단계마다 대응 문서(data-collection.md, DB.md, graph-schema.md, 각 CLAUDE.md) 동반
갱신이 필요하다 — 특히 2·3단계는 서비스 CLAUDE.md의 패키지 구조 설명이 크게 바뀐다.

## 5. 미리 정할 것

1. **`jira_key` → `issue_key` 개명 여부** — 권장은 "개명". 데이터가 커지기 전인 지금이
   가장 싸다. (§3-3 참고)
2. **provider ID 표기 통일** — RDB/API는 소문자(`linear`), 그래프 source는 대문자(`LINEAR`),
   alias 접두는 `LINEAR:` — 현행 규칙을 계약 문서에 명문화한다. Google Chat처럼 두 단어인
   경우의 표기(`google-chat` vs `GOOGLE_CHAT`)만 지금 정해 둔다.
