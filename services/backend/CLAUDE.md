## 실행 명령어

```bash
cd services/backend
./gradlew bootRun
./gradlew test
./gradlew test --tests "패키지.클래스명"
./gradlew build
```

## 패키지 구조

패키지는 기능 단위로 나눈다. `auth`, `github`, `project`, `integration`, `conversation`, `graph` 아래에 `controller/service/repository/domain/dto`를 둔다(기능별로 일부 계층은 생략한다). `graph`는 자체 저장소 없이 ai-engine 그래프 조회를 프록시한다. `jira`는 OAuth 클라이언트(동의 코드 교환·토큰 갱신·사이트/프로젝트 조회)와 provider 전략 구현을, `slack`은 연동 검증용 client와 provider 전략 구현을 둔다. `discord`는 OAuth 클라이언트(code 교환·grant 폐기·봇 길드 퇴장)와 provider 전략 구현을 둔다 — 수집은 앱 전체가 공유하는 봇 토큰으로 하고, 행에 저장하는 사용자 OAuth 토큰(refresh token만)은 해제 시 grant 폐기에만 쓰인다. `googlechat`은 OAuth 클라이언트(code 교환·토큰 갱신·grant 폐기·스페이스 목록 조회)와 provider 전략 구현(SPI 4종 전부 — Jira와 같은 조합)을 둔다. 자격증명 코덱·`GoogleChatTokenService`는 Jira와 같은 이유로 `integration.service`에 둔다(SPI 구현체를 leaf로 유지하기 위해 잠금·트랜잭션을 쓰는 무거운 부분을 떼어냈다). `notion`은 OAuth 클라이언트(code 교환·폐기 — 둘 다 Basic auth + JSON 바디, 폼 인코딩을 쓰는 다른 provider와 다르다)와 provider 전략 구현(SPI 2종 — 폐기만, Slack·Discord와 같은 조합)을 둔다. 선택 단계가 없고(동의 화면의 페이지 피커가 곧 선택) 갱신도 구현하지 않는다(access token 비만료 취급 — 갱신 응답에 만료 정보가 없다). 자격증명 코덱(`NotionCredential`/`NotionCredentialCodec`)은 Google Chat과 같은 이유로 `integration.service`에 둔다. 전역 코드는 `common`, `config`, `security`, pipeline 공유 테이블은 `shared`에 둔다.

`dto`에는 직렬화 경계 타입(프론트 요청·응답, ai-engine 클라이언트 DTO, opaque 커서)만 두고 필드에 도메인 엔티티를 노출하지 않는다(엔티티는 `from()` 매핑 파라미터로만 받는다). 도메인 엔티티를 필드로 담는 서비스 반환·중간 타입(예: `ConversationStart`, `ConversationPage`, `ConversationDetail`)은 `service`에 둔다.

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

- `AiEngineConfig`는 `ai.engine.url` 기반 `aiEngineRestClient`와 connect(3s)/read(60s) timeout을 구성한다.
  timeout이 없으면 ai-engine hang 시 Tomcat 스레드가 무한 점유돼 fallback/502가 작동하지 못한다.
  빌드는 비동기 202라 짧고, read(60s)는 LLM tool-calling 질의(/query)를 위한 여유다.
- 그래프 데이터의 단일 소유자는 ai-engine(Neo4j)다. backend는 인가를 통과시킨 뒤 조회·삭제를 프록시만 한다.
  - 엔드포인트 계약의 단일 출처는 `AiEngineGraphClient`(그래프 조회·빌드·삭제)와 `AiEngineQueryClient`(질의·요약) 코드다 — 메서드 목록을 여기에 중복 기재하지 않는다.
  - ai-engine 호출 실패는 `BadGatewayException`(502)으로 변환한다. 단 대화 질의(`AiEngineQueryClient`)는 예외 대신 fallback 답변을 반환해 대화 흐름을 유지한다.
  - 그래프 재구축은 비동기다: `POST /api/v1/projects/{projectId}/graph/build?verify=`가 즉시 202를 반환하고, `GET .../graph/build/status`로 완료를 폴링한다. `projectId`는 인가 게이트이자 실제 빌드 대상이다. `verify=false`(기본)는 임베딩 유사도만으로 시맨틱 엣지를 만들고, `verify=true`는 임베딩으로 후보를 선별한 뒤 LLM이 텍스트를 검증해 재구축한다(비용↑ 정확도↑).
- 모든 ai-engine 호출은 `projectId`로 스코프해 다른 프로젝트 데이터 인용을 차단한다.

## 대화(conversation) 처리

- `MessageService.addMessage`는 트랜잭션을 2단계로 분리한다: (1) 사용자 메시지 저장, (2) ai-engine 질의(트랜잭션 밖) 후 assistant 응답 저장. 느린 AI 질의 중 DB 커넥션 점유를 피하고, 질의 실패와 무관하게 사용자 메시지를 보존하기 위함이다.
- 최신 턴부터 거꾸로 글자 수(user+assistant content 길이)를 누적해 `conversation.memory.history-budget-chars`(기본 32000자) 이내에 드는 완성 턴만 history로 ai-engine에 전달하고, 그보다 오래된 턴은 running summary로 누적 압축한다. 턴은 항상 통째 단위(부분 포함 없음)이며 최신 턴은 예산을 넘겨도 무조건 포함한다. fallback/blank로 끝난 턴은 history·요약에서 제외한다. 예산 창 밖으로 밀렸지만 아직 요약 트리거를 못 채운 백로그 턴(사각지대)은 `summary-trigger-chars`를 캡으로 재사용해 history 앞쪽에 원문 그대로 동승시키고, 캡을 넘는 턴부터는 통째로 제외한다(요약 대상 범위에는 영향 없음).
- running summary 갱신은 백로그(요약 대상 턴의 content 길이 합)가 `conversation.memory.summary-trigger-chars`(기본 8000자) 이상일 때만 `summaryTaskExecutor` 전용 풀에서 비동기로 실행한다. 이번 턴의 질의는 갱신 완료를 기다리지 않고 항상 저장돼 있던 기존 요약을 사용한다. version 기반 CAS로 동시 갱신 충돌을 막으며, 요약 생성이 연속 실패하면 `SummaryBackoffTracker`(인메모리, 재시작 시 리셋)가 일정 턴 동안 재시도를 건너뛴다.
- 직전 정상 응답의 `structured.evidence`에서 후속 질문 대상 식별용 prior evidence를 추출해 함께 전달한다.

## 외부 연동 (OAuth)

### provider 전략 — 새 연동 추가 지점

> 커넥터 1개의 전체 순서(외부 앱 등록 → backend → pipeline-worker → 프론트)는
> `docs/integration-abstraction.md`의 「커넥터 엔드투엔드 체크리스트」에 있다. 아래는 그중 backend 몫이다.

provider별 차이는 SPI 구현으로만 표현한다. `integration` 패키지의 오케스트레이션(컨트롤러·
`IntegrationOAuthService`·`IntegrationService`)에는 provider 분기(switch)를 두지 않는다.

| SPI | 책임 | 구현하는 provider |
|-----|------|------------------|
| `OAuthConnectFlow` | 동의 URL 조립, code → `OAuthConnection`(자격증명 평문 + 수집 대상 참조) 교환 | OAuth로 붙는 전부 |
| `ProviderCredentialLifecycle` | 연동 해제 시 자격증명 폐기(`revoke(encryptedCredential, externalRef)`) | 원격 폐기 수단이 있는 provider |
| `AccessTokenRefresher` | 만료 임박 access token 갱신(`ensureFreshAccessToken`) | **만료되는 토큰을 쓰는 provider만** |
| `IntegrationSelectionFlow` | 동의 후 "무엇을 수집할지" 고르는 **단계 선언**과 단계별 후보 조회 | 선택 단계가 있는 provider만 |

전부 provider client에만 의존하는 leaf다 — `IntegrationService`가 이들을 주입받으므로 구현체에서
다시 `IntegrationService`를 참조하면 순환 의존이 된다. 레지스트리는 SPI마다 하나씩 둔다.

**provider가 무엇을 지원하는지는 빈 등록 여부로 표현하고, boolean 플래그나 기본 no-op 메서드로
신고하지 않는다.** 플래그는 구현하고 켜는 것을 빠뜨릴 수 있고, 기본 no-op은 "지원하지 않음"과
"아무 일도 필요 없음"을 호출부가 구분할 수 없게 만든다 — 둘 다 조용히 틀린 동작으로 끝난다.
`AccessTokenRefresher`를 `ProviderCredentialLifecycle`에서 떼어낸 이유가 이것이다
(아래 「내부 서비스 API」 참고). `ProviderCredentialLifecycle`도 같은 원칙을 따른다 —
레지스트리는 `find(provider): Optional`로 등록 여부를 신고하고, 미등록 provider는 폐기를
건너뛴다(`IntegrationRevocationService.revoke`가 `find`+`ifPresent`로 호출).

**폐기 실행은 `IntegrationRevocationService`(leaf)가 소유한다.** 연동 해제는 단건(`revoke`),
프로젝트 삭제·사용자 파기는 일괄(`revokeAll(projectId)`)로 부른다. `IntegrationService`가
`ProjectService`를 주입받으므로 폐기 로직이 거기 있으면 `ProjectService`에서 부를 수 없다 —
그래서 둘 다 참조할 수 있는 leaf로 뺐다. `revokeAll`은 **건별로 실패를 삼킨다**: 한 provider의
폐기 실패가 나머지 연동의 grant를 영구히 남기면 안 되기 때문이다.

**저장 정책은 `IntegrationService.connectOAuth`가 소유한다** — 확정 연동 409 선검사(1회용 code를
교환 전에 지킨다) → code 교환 → 자격증명 암호화 → 저장(pending 행이면 재동의로 덮어쓰기, unique 위반은
409로 변환) → 수집 트리거. 새 connect flow는 이 메서드를 고치지 않는다.

저장이 409로 끝나면(선검사와 저장 사이 경합) **방금 교환한 자격증명을 해제와 같은
`ProviderCredentialLifecycle.revoke`로 폐기한 뒤 409를 올린다** — 저장되지 않았으니 나중에 폐기할
수단이 없고, 그대로 두면 provider 쪽에 grant가 남는다(Discord는 동의 승인 순간 서버에 들어간 봇까지
남는데 `guild_id`를 저장하지 않아 내보낼 방법이 사라진다). 정리 실패는 삼키고 로그만 남긴다 —
원래의 409를 가리면 사용자가 실패 이유를 잃는다.

**단 선검사(code 교환 전) 경로는 정리할 수 없다.** Discord는 동의 승인만으로 봇이 서버에 들어가는데
code를 교환하지 않아 어느 서버인지 모른다. 여기서 정리하려면 검증 전 외부 호출 금지라는 이 메서드의
성질을 뒤집어야 하고, 교환으로 생긴 grant까지 따로 폐기해야 해서 에러 경로에 외부 호출이 3번 붙는다.
10분(state TTL) 안의 중복 개시 경합에서만 생기는 상태라 자동 정리 대신 **프론트가 사용자에게 알린다**
(`sourceCatalog`의 `consentSideEffect` → `already_connected` 안내에 덧붙는다).
자격증명 **형태**만 provider 몫이다(토큰 문자열 하나든, 갱신값을 담은 JSON이든 평문으로 넘기면
암호화는 공용 코드가 한다). pending 여부도 flow가 신고하지 않고 `IntegrationSelectionFlow` 등록
여부로 갈린다 — 두 SPI의 선언이 어긋나 영영 확정할 수 없는 행이 생기는 것을 막기 위함이다.

해당 동작이 없는 provider는 **빈을 만들지 않으면 된다** — Slack·Discord·Notion은 폐기만 있고 갱신은 없어
`ProviderCredentialLifecycle`만, Jira·Google Chat은 갱신·선택·폐기 셋 다 있어 전부, GitHub은
자격증명이 없어 어느 쪽도 없다.
Discord의 `revoke`는 자격증명(refresh token)뿐 아니라 `externalRef`의 `guild_id`도 쓴다 — 봇이 길드를
나가는 것이 실질적인 폐기라서다(A8로 시그니처를 넓힌 이유). Google Chat은 남겨질 봇이 없어
`externalRef`를 쓰지 않고 refresh token(grant) 폐기만으로 끝난다.

라우트는 `{provider}` 하나로 합쳐져 있고 **기존 URL은 그대로 해석된다** —
`/api/v1/integrations/slack/callback`은 Slack·Atlassian 앱에 등록된 redirect URI라 바꾸면 배포된 연동이 깨진다.
알 수 없거나 OAuth로 붙지 않는 provider는 라우트가 없던 것과 같게 404다.

### 다단 선택 (선택 단계가 있는 provider)

동의만으로 끝나지 않는 provider는 `IntegrationSelectionFlow`로 자기 단계를 선언한다. 백엔드·프론트는
단계 수나 이름을 하드코딩하지 않는다 — 조사해 보니 Linear·Google Chat은 1단(각각 team·space),
Jira·Asana는 2단, ClickUp은 workspace → space → *folder(선택)* → list로 최대 4단이고
**중간 단계를 건너뛸 수 있다**.

- `SelectionStep.key`·`labelKey`는 그대로 `external_ref` 키가 된다 — pipeline-worker가 수집할 때 읽는
  키와 같아야 하므로 provider가 자기 키 이름을 정한다(Jira는 `cloud_id`·`project_key`를 그대로 유지한다).
- 확정 전 상태는 `external_ref.status = pending_selection`이다. 구 Jira 전용 값(`pending_project`)도
  읽기에서 pending으로 인정한다 — 이미 저장된 행이 있어 데이터 마이그레이션 없이 넘어가기 위함이다.
- 확정은 한 번에 한다(부분 저장 상태를 만들지 않는다): 단계 선언 조회 → 단계별 후보 조회 → 전체 선택 제출.
- 재동의 시 자동 복원은 **필수 단계를 전부** 다시 확인한다 — 사이트는 살아 있는데 프로젝트가 지워진
  경우까지 걸러야 하기 때문이다.

### 공통 규칙

- GitHub은 App installation, Slack·Jira·Discord·Google Chat은 OAuth 동의 흐름으로만 붙인다.
  **토큰을 사용자가 직접 입력하는 경로는 없다.**
  Discord만 예외적으로 수집 자체는 OAuth 토큰이 아니라 앱 전체가 공유하는 봇 토큰으로 한다(REST로 메시지
  히스토리를 읽으려면 봇 토큰이 필요하다) — 행에 저장하는 사용자 OAuth 토큰(refresh token)은 해제 시
  grant 폐기에만 쓰인다.
- 선택 단계 API는 `GET .../integrations/{provider}/selection/steps`,
  `GET .../selection/options?step=&{앞 단계 키}=`, `POST .../selection`이다.
- `POST /api/v1/projects`에 `github` 블록이 있으면 프로젝트 생성과 GitHub 연동을 **한 트랜잭션**으로 처리한다
  (`IntegrationService.createProjectWithGitHubRepository`). 온보딩에서 프로젝트만 만들어지고 사용자가 이탈해
  GitHub 없는 빈 프로젝트가 남는 것을 막기 위함이다. 설치 토큰 발급(외부 호출)은 트랜잭션 시작 전에 끝내고,
  수집 트리거는 커밋 뒤에 한다.
- `DELETE /api/v1/projects/{projectId}/integrations/{provider}`(연동 해제)는 provider 권한 폐기 →
  그래프 삭제 → RDB(연동 행·checkpoint) 삭제 순서다. **권한 폐기가 가장 먼저인 이유**: 우리 DB의
  토큰을 지우면 폐기에 쓸 값 자체가 사라진다. Slack은 `auth.revoke`, Jira·Google Chat은 refresh token
  폐기(파생 access token도 함께 무효화)이며, 폐기 실패는 각 client가 로그만 남기고 삼킨다 —
  이미 폐기된 토큰이나 provider 장애로 해제가 막히면 사용자가 데이터를 지울 방법을 잃는다.
  Linear는 refresh token을 직접 폐기(파생 access token도 함께 무효화)하며, Asana도 refresh
  token을 폐기한다(비회전이라 최초 발급 값을 그대로 유지해 오던 값이다). GitHub은
  폐기 대상이 없다(App 설치는 계정 단위 유지, installation token은 1시간 캐시). ClickUp도
  폐기 대상이 없다(원격 revoke API 자체가 없음 — 앱 권한 해제는 사용자가 ClickUp 설정의
  Apps에서 직접 한다). Notion은 access_token을 폐기한다(`POST /v1/oauth/revoke`, Basic auth —
  refresh_token이 아니라 access_token으로 호출하는 계약이다).
  **그래프가 RDB보다 먼저** — 프로젝트 삭제와 같은 이유다(외부 HTTP를 트랜잭션 밖에 두고,
  그래프 삭제가 멱등이라 재시도로 수렴).
  checkpoint를 반드시 함께 지운다 — 남기면 재연결이 옛 커서부터 증분 수집을 재개해 그 사이
  데이터가 영구 누락된다. GitHub App 설치(`github_installations`)는 계정 단위라 건드리지 않는다.
- **RDB 밖 자원(Neo4j 그래프·provider 권한)을 가진 삭제는 FK CASCADE에 맡기지 않는다.**
  `users`를 지우면 프로젝트·연동·대화·checkpoint가 CASCADE로 사라지지만, 그래프와 provider 쪽
  grant는 남고 **행이 사라져 나중에 지울 수단마저 없어진다**(고아 그래프). 그래서 삭제 경로 셋이
  같은 순서를 공유한다 — 권한 폐기 → 그래프 삭제 → RDB 삭제.
  - 연동 해제: `IntegrationService.disconnect` (단건 폐기 + 소스 단위 그래프 삭제)
  - 프로젝트 삭제: `ProjectService.deleteProject` (일괄 폐기 + 프로젝트 그래프 삭제)
  - 사용자 파기: `UserPurgeService` → `ProjectService.releaseExternalResources`로 소유 프로젝트의
    폐기·그래프 삭제를 끝낸 뒤에만 `users` 행을 지운다. 실패한 사용자는 행을 남겨 다음 회차에
    재시도한다 — **실패했는데 행을 지우면 그 순간 고아 그래프가 된다.**
    `releaseExternalResources`는 파기 전용이라 `getActiveUser` 게이트를 타지 않는다(대상이 이미
    soft-delete 상태라 그 검증에서 예외가 난다).
- 콜백 요청에는 사용자 JWT가 없다. 서명된 state(`OAuthStateService`)가 신원·프로젝트 소유권을 증명하는 유일한 수단이므로,
  authorize URL 조립 시 소유권을 확인하고 state를 발급한다.
- 콜백은 예외를 던지지 않고 항상 프론트로 302 리다이렉트하며, 실패는 `?error=` 코드로 전달한다.
  state 위조·만료는 `projectId`를 복원할 수 없어 로그가 유일한 관측 수단이다.
- Jira만 2단계다: 동의 직후에는 토큰만 담은 pending 행을 만들고, 사용자가 사이트·프로젝트를 고르면 확정한다.
- Jira·Google Chat access token은 둘 다 1시간 안팎으로 짧아 각각 `JiraTokenService`·
  `GoogleChatTokenService`가 갱신을 전담한다 — pipeline-worker는 직접 갱신하지 않고 아래 내부 API로
  위임한다. 다만 refresh token 갱신 정책은 정반대다: Atlassian은 갱신할 때마다 새 refresh token을
  내려주므로(회전) **갱신 주체가 둘이면 서로의 토큰을 무효화한다** — 반드시 새 값을 덮어써야 한다.
  Google은 갱신 응답에 refresh token을 다시 주지 않으므로(비회전) 응답에 없으면 기존 값을 그대로
  보존해야 한다 — Jira 패턴을 그대로 복사하면 여기서 조용히 깨진다.
- Linear access token도 1시간짜리라 `LinearTokenService`가 갱신을 전담하며, `LinearOAuthClient.refresh`
  응답의 refresh token을 매번 그대로 덮어쓴다 — Jira와 같은 회전 가정이다.
- Asana access token도 1시간짜리라 `AsanaTokenService`가 갱신을 전담한다. Asana refresh token은 회전하지
  않으므로 Jira·Linear 같은 무효화 문제는 없다.

## 내부 서비스 API

- `/api/v1/internal/**`는 사용자 JWT가 아니라 `X-Internal-Service-Token` 헤더로 인증한다.
- `InternalServiceAuthenticationFilter`는 `security.internal-service.token`과 요청 헤더를 timing-safe 방식으로 비교한다.
- `POST /api/v1/internal/github/installations/{installationId}/token`은 GitHub installation access token이 없거나 만료 임박한 경우 갱신해 DB 캐시를 보장하고 `204`를 반환한다. 토큰 평문은 응답하지 않는다.
- `POST /api/v1/internal/integrations/{projectId}/{provider}/token`은 access token이 없거나 만료 임박한 경우 갱신해 저장하고 `204`를 반환한다(Jira·Google Chat은 refresh token으로 갱신하며, 폐기돼 영구 실패하면 연동을 pending 상태로 되돌린다). 토큰 평문은 응답하지 않는다.
  **실패 응답은 두 뜻을 서로 다른 코드로 갈라 답한다 — 섞으면 안 된다.**
  - `501` — 이 provider에는 갱신 수단이 없다(**능력**에 대한 답). 조용한 `204` 대신 이걸 반환해 호출부가 갱신됐다고 오인한 채 만료된 토큰으로 수집하는 것을 막는다. **판정 기준은 `AccessTokenRefresher` 등록 여부이며**, 폐기 등 다른 자격증명 동작이 있다는 이유로 통과시키면 안 된다(폐기만 있고 갱신은 없는 Slack·Discord가 그 경우 조용한 `204`를 받았다). 호출부는 저장된 자격증명 그대로 진행하므로 Slack·Discord 수집이 깨지지 않는다.
  - `404` — 연동 행이 없거나(해제 직후 레이스) 알 수 없는 provider다(**리소스**에 대한 답). 호출부는 이번 수집에서 그 provider를 건너뛴다.

  둘 다 `404`이던 시절에는 해제 직후 레이스가 "갱신 불필요"로 읽혀 폐기된 토큰으로 수집을 진행했다(폐기가 실패해 토큰이 살아 있으면 방금 지운 그래프가 되살아난다). Jira 전용이던 시절에는 Jira에 갱신기가 항상 있어 `404`의 뜻이 하나뿐이라 문제가 없었는데, 갱신기 없는 provider까지 이 API를 쓰면서 한 코드에 두 뜻이 겹쳤다.
  호출부는 pipeline-worker `IntegrationTokenClient`다(Google Chat 추가를 계기로 Jira 전용이던 `JiraTokenClient`를 provider 인자를 받는 형태로 일반화했다).
- `POST /api/v1/internal/atlassian/consent`는 봇 계정 동의 code를 앱 수준 자격증명으로 교환·저장한다(최초 1회). 토큰 평문은 응답하지 않는다.
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

