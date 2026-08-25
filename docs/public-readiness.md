# 공개 전환 점검표 (불특정 다수 대상)

> 지금의 배포를 **불특정 다수가 쓰는 서비스**로 바꾸려면 무엇을 고쳐야 하는지를, 막히는 순서대로 모은다.
> 배포 절차 자체는 [deployment.md](deployment.md), 배포 경로의 미완 항목은
> [deployment-followups.md](deployment-followups.md)를 본다 — 이 문서는 그 둘이 다루지 않는
> "여러 사람이 쓴다"는 조건에서만 생기는 것들이다.
>
> 근거는 전부 `develop`(7b477c4)의 실제 코드·설정에서 확인했다. 파일 경로와 줄 번호는 그 시점 기준이다.

---

## 먼저 — 격리는 이미 되어 있다

"남의 데이터가 섞인다"는 종류의 문제는 없다.

- 모든 도메인 노드가 `(project_id, 자연키)` 복합 유니크다
  ([graph/schema.py](../services/ai-engine/graph/schema.py) `_UNIQUE_CONSTRAINTS`).
  자연키(pr_number·path·issue_key)는 프로젝트마다 충돌하므로, 이 제약이 없으면 서로 다른
  프로젝트가 같은 노드로 병합된다.
- `/query`의 모든 그래프 조회가 `project_id`로 스코프된다. `project_id`가 없으면 어떤 노드에도
  매칭되지 않아 빈 답변이 된다(안전한 degradation). 인가는 backend가 전담하고 ai-engine은
  내부 서비스로 신뢰한다.

막고 있는 것은 격리가 아니라 아래 층위들이다.

## 요약

| 층 | 무엇이 문제인가 | 항목 | 성격 |
|---|---|---|---|
| **0층** | 기능이 아예 막힌다 | 4 (**1 완료**) | 코드 변경 |
| **1층** | 비용·쿼터를 전원이 공유한다 | 3 | 코드 변경 + 정책 |
| **2층** | 인증·시크릿·세션에 구멍이 있다 | 4 | 코드 변경 |
| **3층** | 외부 앱이 공개 배포 상태가 아니다 | 9종 | **외부 심사 대기** |
| **4층** | 법적 고지·동의·파기가 비어 있다 | 5 (**1 완료**) | 정책 + 코드 변경 |
| **5층** | 운영 장치가 없다 | 4 | 인프라 |
| **6층** | 규모가 커지면 깨진다 | 3 | 구조 변경 (나중) |

진행: **M0** = 4-1(탈퇴 시 파기 누락) · **M1a** = 0-4(운영자 PAT 제거) · **M1b** = 0-1(조직 레포) · 4-2(연락처).
다음은 **M2** = 0-2(팀 공유) — 다만 공개 범위를 정해야 착수 여부가 갈린다.

**공개 여부와 무관하게 지금 이미 결함인 것이 하나 있었다** — 4-1(탈퇴 시 파기 누락).
공개 계획과 별개라 **가장 먼저 고쳤다(M0).** 진행 상황은 4-1 절을 본다.

---

## 0층 — 기능이 아예 막힌다

비용도 심사도 그다음 문제다. 이걸 놔두면 공개해도 제대로 써볼 수 있는 사람이 거의 없다.

### 0-1. 조직(Organization) 레포를 아무도 못 쓴다

로그인 시 GitHub 설치 목록을 동기화하는데 **본인 개인 계정 설치만** 통과시킨다.

```java
.filter(installation -> gitHubUser.login().equalsIgnoreCase(installation.account().login()))
```
[AuthService.java:140](../services/backend/src/main/java/com/history/backend/auth/service/AuthService.java)

조직에 앱을 설치하면 `account.login`이 **조직 이름**이라 개인 로그인 이름과 절대 일치하지 않고,
목록에 아예 뜨지 않는다. 이 필터는 "App manager 권한이 있으면 남의 설치까지 반환된다"는 문제를
막으려고 넣은 것인데(바로 위 주석), 부작용으로 조직 설치가 함께 걸러진다.

문제는 **이 서비스의 타깃이 정확히 그 사람들**이라는 점이다. 팀 단위로 협업하는 레포는 대부분
조직 소유이고, 개인 레포만 쓰는 사람은 애초에 "왜 이렇게 바뀐 거지"를 여럿이 물을 일이 적다.

- [x] 계정 이름 일치가 아니라 **이 사용자가 그 설치에 실제 접근 권한이 있는가**를 기준으로 필터를 다시 짰다
- [x] 조직 설치를 동기화 대상에 넣고, 접근권을 조인 테이블로 분리해 여러 사용자가 공유하게 했다
- [x] **실동작 검증** — 테스트 조직(`virtual-investment-project`)으로 확인 완료 (아래)
- [ ] **2계정 공유는 미검증** — 두 번째 사용자가 같은 설치의 멤버가 되는 경로. 코드 경로는 1계정
      조직 설치와 동일하고 퍼시스턴스·서비스 테스트가 덮지만, 실동작으로는 못 봤다.
      **제한 공개 전에 한 번 확인한다** (팀원 계정으로 로그인 → 그 조직 설치의 멤버수가 2가 되는지)

#### 수정 내용 (M1b)

| 무엇 | 어디 |
|---|---|
| 접근권을 조인 테이블로 분리 | `V17__share_github_installations.sql` — `github_installation_users` + 기존 행 백필 |
| 탈퇴 연쇄 삭제를 끊음 | 같은 migration — `installer_user_id`를 nullable + **`ON DELETE SET NULL`**(V6의 CASCADE 해제) |
| 인가 기준 전환 | `GitHubInstallationService.getAccessibleInstallation` — 멤버십 조회. `installer_user_id`로 인가하는 곳은 0곳 |
| 최초 설치자 덮어쓰기 제거 | `updateAccount`에서 installer 파라미터 제거 — 소유권이 조용히 넘어가던 원인 |
| 동기화 판정 교체 | `AuthService.syncInstallations` — 내 개인 설치는 무검증 통과, 그 외는 `GET /user/installations/{id}/repositories`로 접근권 확인 |
| 로그인 경로 타임아웃 결함 수정 | `GitHubOAuthClient`가 타임아웃 없는 자체 `RestClient`를 쓰고 있었다 → `gitHubRestClient`(3s/10s) 주입 |

backend 테스트 **761개 통과**.

**실동작 검증 결과 (2026-08-25, 로컬 `./dev.sh` 스택 + 테스트 조직)**

| 확인 | 결과 |
|---|---|
| 조직 설치 동기화 | ✅ `Organization / virtual-investment-project` 행 생성 — 옛 필터로는 불가능 |
| 접근 검증 | ✅ 조직은 무조건 검증 경로를 타며 통과, backend warn 0건 |
| 멤버십 인가 → 화면 | ✅ 온보딩 저장소 목록에 조직 레포 4개가 개인 레포와 함께 표시 |
| 조직 installation token | ✅ 발급됨 — 조직 설치로는 처음 타보는 경로 |
| 백필 (실데이터) | ✅ 기존 설치 2건 → 멤버십 2건, 누락 0 |
| FK `SET NULL` | ✅ `pg_constraint` 실조회로 확인 |
| 개인 설치 회귀 | ✅ 기존 2건 정상 유지 |

**파기 시나리오는 실행하지 않았다** — compose가 `USER_PURGE_*` 환경변수를 backend에 전달하지 않아
유예 30일·cron 03:30을 줄일 수단이 없다. FK가 `SET NULL`인 것을 실DB에서 확인했고 퍼시스턴스
테스트 2개가 "설치자 삭제 시 설치 행 생존"을 검증하므로 스키마 성질로는 증명됐다고 본다.

**우선순위: 최상 — 완료.** (2계정 공유만 제한 공개 전 확인 대상으로 남음)

### 0-2. 팀이 프로젝트를 함께 볼 수 없다

프로젝트는 **소유자 1명**에게만 속한다. 목록·조회·삭제가 전부 `owner_id` 스코프이고
([Project.java:34](../services/backend/src/main/java/com/history/backend/project/domain/Project.java),
`ProjectService`의 모든 조회), 팀원 초대나 공유 개념이 없다.

연동 유니크 키가 `(project, provider)`라
([V3__create_integrations.sql:28](../services/backend/src/main/resources/db/migration/V3__create_integrations.sql))
**팀원마다 같은 레포를 각자 연동하는 것은 막히지 않는다.** 그러면 같은 레포를 N번 수집해
수집 비용도 그래프도 N벌이 되고, 그 N벌은 서로 다른 프로젝트라 답변도 갈라진다.

팀의 의사결정 맥락을 다루는 제품인데 정작 그 맥락을 팀이 나눠 볼 수 없다는 것이 문제의 본질이다.

- [ ] 프로젝트 멤버십(초대·역할)을 도입할지 결정한다 — 도입하면 인가 경로 전반이 바뀐다
- [ ] 최소안: **같은 레포가 이미 다른 프로젝트에 연결돼 있음**을 감지해 사용자에게 안내한다

**우선순위: 상.** 제한 공개에서는 권장, 완전 공개에서는 필수.

### 0-3. Slack 수집이 사실상 못 쓰는 속도다

2025-05-29 이후 만들어진 비마켓플레이스 앱은 `conversations.history`·`replies`가
**분당 1회·요청당 15건**으로 묶인다
([pipeline-worker/CLAUDE.md:156](../services/pipeline-worker/CLAUDE.md),
[data-collection.md:387](data-collection.md)).

코드는 이 상황을 이미 알고 있다 — `SlackPacing`이 429의 `Retry-After`를 받아 호출 간격을
승격시키며 **버티게** 만들어져 있다. 다만 그건 견디는 장치이고, 한도 자체는 Marketplace 승인
전에는 풀리지 않는다. 사용자가 여럿이면 그 한도를 전원이 나눠 쓴다.

- [ ] Slack Marketplace 승인을 신청한다 (리드타임이 길다 — 착수가 늦으면 그대로 일정이 된다)
- [ ] 승인 전까지 Slack을 공개 범위에서 뺄지, "느린 수집"임을 제품에서 명시할지 정한다

**우선순위: 상 (외부 대기).**

### 0-4. 운영자 PAT로 남의 레포를 읽으려 한다 — ✅ 수정 완료 (M1a)

> **2026-08-24 수정.** ai-engine이 GitHub을 직접 부르는 경로를 전부 제거했다.

ai-engine이 프로젝트 컨텍스트(README 요약)를 만들 때 `GITHUB_TOKEN`, 즉 운영자 개인 PAT를 썼다.
불특정 다수 대상에서는 남의 비공개 레포를 어차피 못 읽고, **읽히는 경우가 오히려 문제였다** —
운영자 권한으로 임의 레포를 읽는 통로가 된다. ai-engine에는 인증이 없어(라우터에 `Depends`·
미들웨어 0건) 도달 가능한 누구든 `owner/repo`를 지정해 호출시킬 수 있었다.

조사에서 드러난 것: **이 경로는 프로덕션에서 한 번도 실행된 적이 없다.** backend의
`AiEngineQueryRequest`에 `repo` 필드가 없어 `req.repo`가 항상 빈 문자열이었고, eval 러너도
보내지 않는다. 그래서 "installation token으로 교체"가 아니라 **제거**가 맞다고 판단했다.

- [x] ai-engine의 GitHub 호출·`GITHUB_TOKEN`·`GITHUB_REPO` prewarm 제거 (`graph/project_context.py` 삭제)
- [x] `QueryRequest.repo`·`orchestrator.run(project_context=...)` 계약 제거 — 후속 설계가 쓰지 않는다
- [ ] **프로젝트 컨텍스트 재설계** — pipeline-worker가 README를 `Document`로 발행하는 방식.
      설계와 열린 결정은 [query-followups.md §6](query-followups.md)에 기록했다. **우선순위 낮음**
      (답변 품질 기능이고 효과 미검증 — 안전성은 위 제거로 이미 해결됐다)

**우선순위: 상 — 완료.**

---

## 1층 — 비용과 쿼터를 전원이 공유한다

코드에 제한이 하나도 없다. URL을 아는 사람은 전부 들어와서 프로젝트를 만들고 질의할 수 있고,
그 비용은 전부 운영자에게 청구된다.

### 1-1. 가입·사용량 제한이 전무하다

GitHub 계정만 있으면 누구나 로그인이 통과한다
([AuthService.java:83](../services/backend/src/main/java/com/history/backend/auth/service/AuthService.java)).
초대코드·웨이팅·도메인 제한 어느 것도 없고, 프로젝트 개수 상한도
([ProjectService.java:31](../services/backend/src/main/java/com/history/backend/project/service/ProjectService.java)),
질의 횟수·토큰 예산도, API rate limit도 없다(관련 의존성 자체가 백엔드에 없다).

- [ ] 사용자별 사용량 테이블 + 일일 질의·토큰 예산
- [ ] 프로젝트 수 상한, 수집 규모 상한(커밋·채널 수)
- [ ] 가입 게이트(초대코드·웨이팅)나 결제 중 하나를 정한다

**우선순위: 최상.** 이게 없으면 공개 즉시 청구서로 돌아온다.

### 1-2. OpenAI 키 하나를 전 사용자가 쓴다

연동 즉시 전체 히스토리를 수집하고, 커밋마다 diff 요약과 임베딩 호출이 일어난다.
큰 레포 하나만 붙여도 그대로 청구서가 된다. 제한이 없으니 한 사람이 여러 개를 연결하면 곱해진다.

- [ ] 프로젝트 단위 비용 계정(accounting)을 붙인다
- [ ] 예산 소진 시 수집·질의를 막는 경로를 만든다

**우선순위: 최상.**

### 1-3. provider 쿼터도 앱 전체가 공유한다

Discord는 **앱 전역 봇 토큰 하나**로 수집하므로 전 테넌트가 한 봇의 한도를 나눠 쓴다
(전역 250ms 고정 딜레이,
[pipeline-worker application.yaml](../services/pipeline-worker/src/main/resources/application.yaml)).
Google Chat 쿼터는 Cloud 프로젝트당이고, GitHub PAT도 시간당 한도가 하나다.
한 사용자의 대량 수집이 다른 사용자를 막는다.

- [ ] 테넌트별 페이싱(공정 큐)으로 바꾸거나, 한 프로젝트가 전체 한도를 독점하지 못하게 상한을 건다

**우선순위: 중.**

---

## 2층 — 인증·시크릿·세션에 구멍이 있다

지금은 노출돼 있지 않지만, 방어가 코드가 아니라 **배포 설정**에 걸려 있다.

### 2-1. pipeline-worker 인바운드에 인증이 없다

`POST /api/v1/collect/{provider}`와 `/api/v1/raw/*`는 누구나 부를 수 있고, 막는 것은 세 겹의
설정뿐이다 — 포트 미개방, 터널 ingress 미등록, nginx의 좁은 webhook prefix
([deployment.md §4-4](deployment.md)). **어느 하나만 어긋나도 열린다.**

상세와 결정 사항은 [deployment-followups.md §4](deployment-followups.md)에 이미 있다.

- [ ] `INTERNAL_SERVICE_TOKEN` 헤더 검증을 pipeline-worker 인바운드에도 적용한다
- [ ] webhook 경로는 GitHub 서명 검증이 있으므로 예외로 둘지 결정한다

**우선순위: 상.**

### 2-2. ai-engine은 admin 라우터까지 무인증이다

query·graph·admin·privacy 네 라우터가 인증 없이 등록된다
([main.py:98-101](../services/ai-engine/main.py)).
admin에는 **그래프 삭제, DLQ replay, Actor 병합·분리** 같은 쓰기가 들어 있다.
현재는 포트 폐쇄만이 방어다.

**스코프도 함께 봐야 한다.** admin 엔드포인트 일부는 `project_id`가 선택값이라 생략하면
**전 프로젝트를 대상으로 돈다** — 예: `POST /slack/filter`는 인자 없이 호출되면
모든 테넌트의 Slack 메시지에 LLM 필터를 적용한다(`run_slack_llm_filter(project_id=None)`).
단일 사용자 시절에는 그게 곧 "내 데이터 전부"라 자연스러웠지만, 여러 테넌트에서는
운영자 실수 한 번이 전원에게 번진다. 인증을 붙일 때 **`project_id`를 필수로 올릴지도 함께 정한다.**

- [ ] 내부 서비스 토큰 검증을 붙인다 (최소한 admin·privacy 라우터만이라도 먼저)
- [ ] 전 프로젝트 스코프로 도는 admin 엔드포인트를 훑어 `project_id` 필수화를 검토한다

**우선순위: 상.**

### 2-3. XSS 한 번에 세션이 통째로 넘어간다

세 가지가 겹친다.

1. access·refresh 토큰이 **`localStorage`**에 있어 스크립트로 읽힌다
   ([tokenStorage.ts:8-15](../clients/web-dashboard/src/auth/tokenStorage.ts))
2. 웹 서버가 보안 헤더를 **하나도** 보내지 않는다 — `Content-Security-Policy`·`X-Frame-Options`·
   `X-Content-Type-Options` 전무 ([nginx.conf](../clients/web-dashboard/nginx.conf)에 `add_header` 0개)
3. refresh 토큰 회전에 **재사용 탐지가 없다** — 탈취된 토큰이 먼저 쓰여도 "Invalid"로 끝날 뿐
   다른 세션을 끊지 않는다 (`RefreshTokenService.rotateRefreshToken`)

혼자 쓸 때는 XSS를 심을 경로가 사실상 없지만, 공개하면 사용자 입력과 외부 데이터(커밋 메시지·
이슈 본문·Slack 대화)가 화면에 들어온다.

- [ ] nginx에 보안 헤더를 추가한다
- [ ] refresh 토큰을 httpOnly 쿠키로 옮긴다
- [ ] 재사용이 감지되면 `revokeAllRefreshTokens`(이미 있는 메서드)로 전 세션을 끊는다

**우선순위: 상.**

### 2-4. 시크릿 회전 경로가 없다

모든 시크릿이 `.env` 한 파일에 모여 있다. `JWT_SECRET`을 바꾸면 전 사용자가 로그아웃되고,
`BACKEND_CREDENTIAL_KEY`를 잃으면 저장된 OAuth 자격증명을 **복호화할 수 없다** —
DB 백업으로도 복구되지 않는다.

- [ ] 키 회전 절차(구·신 키 병행 복호화)를 정한다
- [ ] 백업과 분리된 키 보관처를 정한다

**우선순위: 중.**

---

## 3층 — 외부 앱이 아직 공개 배포 상태가 아니다

전부 "개발자 본인이 쓰는" 전제로 등록돼 있다. **코드 작업이 아니라 남의 심사를 기다리는 일**이라,
착수가 늦으면 그대로 일정이 된다.

| Provider | 해야 할 일 | 리드타임 |
|---|---|---|
| **Google Chat** | 동의 화면 External 전환. `chat.messages.readonly`·`directory.readonly`가 민감 범위라 **Google 검증 + 보안 평가**가 붙는다. 미검증 앱은 사용자 수 상한도 걸린다 | **가장 김** |
| **Slack** | Marketplace 승인 — 0-3과 같은 건이다 | 김 |
| **Atlassian (Jira)** | 앱 Distribution 활성화 + 개인정보 보고(PDR) 의무 이행. 구현은 끝나 있고 `ATLASSIAN_PDR_ENABLED`가 기본 false, 봇 계정 동의(최초 1회)가 미완이다 ([jira-personal-data-policy.md](jira-personal-data-policy.md)) | 중간 |
| **Discord** | 봇 Public 전환 + MESSAGE_CONTENT intent. 100서버 초과 시 앱 verification 별도 ([discord-integration.md:62](discord-integration.md)) | 중간 |
| **GitHub App** | App을 Public으로 전환 (0-1과 함께 검증) | 짧음 |
| **Linear** | 앱 "Public" 토글 — 꺼져 있으면 authorize가 앱을 찾지 못한다 | 짧음 |
| **Notion** | Public connection 유지 확인 (이미 이 전제로 구현) | 짧음 |
| **Asana · ClickUp** | OAuth 앱 공개 등록 (Asana는 granular scope 등록이어야 한다) | 짧음 |
| **공통** | redirect URI 9종을 전부 배포 도메인으로 교체 — 하나라도 로컬 값이 남으면 그 provider의 연동만 조용히 깨진다 ([deployment.md §3-1](deployment.md)) | 짧음 |

- [ ] Google Chat 검증 착수 (임계경로 — 가장 먼저 시작한다)
- [ ] Slack Marketplace 승인 착수
- [ ] Atlassian Distribution + PDR 활성화(봇 계정 동의)
- [ ] Discord Public 전환
- [ ] 나머지 5종 공개 등록 + redirect URI 일괄 교체

---

## 4층 — 법적 고지·동의·파기가 비어 있다

약관·개인정보처리방침 **본문 자체는 이미 상세하게 쓰여 있다**(소스별 수집 항목, 국외 이전까지).
비어 있는 것은 운영 주체와 절차다.

### 4-1. 탈퇴해도 그래프와 provider 권한이 남는다 — ✅ 수정 완료 (M0)

> **2026-08-24 수정.** 아래 세 결함을 모두 고쳤다.
> backend 테스트 745개 통과. 상세는 이 절 끝의 「수정 내용」 참고.

탈퇴는 soft delete와 refresh 토큰 폐기까지만 한다(`UserService.deactivateUser`).
30일 뒤 파기 배치
([UserPurgeService](../services/backend/src/main/java/com/history/backend/auth/service/UserPurgeService.java))는
`users` 행을 지우고, FK CASCADE가 프로젝트·연동·대화를 함께 지운다. **여기서 두 가지가 빠진다.**

**① Neo4j 그래프가 남는다.** 그래프 삭제를 부르는 곳은
[ProjectService.java:122](../services/backend/src/main/java/com/history/backend/project/service/ProjectService.java)
단 하나이고, 파기 배치에는 ai-engine 호출이 없다. 커밋 메시지·이슈 본문·대화·**제3자의 이름과
이메일**이 그대로 남는데, `projectId`를 담은 행이 사라져 **나중에 찾아 지울 수도 없다.**

**② provider 권한이 남는다.** 자격증명 폐기를 부르는 곳도
[IntegrationService.java:465](../services/backend/src/main/java/com/history/backend/integration/service/IntegrationService.java)
(연동 해제 경로) 하나뿐이다. Slack·Jira·Discord·Google Chat·Linear·Asana·Notion의 grant가
살아남고, **Discord 봇은 사용자 서버에 그대로 남는다** — 저장돼 있던 `guild_id`가 지워져
내보낼 방법도 없다.

수동 삭제 경로(프로젝트 삭제·연동 해제)는 순서까지 정확히 지키게 잘 만들어져 있다
(권한 폐기 → 그래프 삭제 → RDB). **파기 배치만 그 경로를 안 탄다.**

- [x] 파기 배치가 사용자의 프로젝트를 **정규 삭제 경로로** 지우게 한다
- [x] 프로젝트 삭제(수동 경로)도 provider 권한을 폐기하게 한다
- [x] 파기 실패 시 그 사용자를 건너뛰고 다음 회차에 재시도한다
- [ ] 이미 고아가 된 그래프가 있는지 조사하고 정리한다 — **남은 작업**(별건, 아래 참고)
- [ ] 파기 실패 알림 — 5-1(모니터링)에 묶여 있다. 지금은 `log.warn`만 남는다

#### 수정 내용

| 무엇 | 어디 |
|---|---|
| 폐기 로직을 leaf 서비스로 추출 (순환 의존 회피) | `integration/service/IntegrationRevocationService` 신규 — `revoke`(단건)·`revokeAll`(프로젝트 일괄, 건별 실패 삼킴) |
| 연동 해제가 위 서비스에 위임 | `IntegrationService.disconnect` (동작 동일) |
| **프로젝트 삭제에 권한 폐기 추가** | `ProjectService.deleteProject` — 폐기 → 그래프 → RDB |
| **파기 배치가 그래프·권한을 먼저 정리** | `ProjectService.releaseExternalResources`(파기 전용, `getActiveUser` 게이트 없음) + `UserPurgeService` |
| 실패한 사용자는 행을 남겨 재시도 | `UserPurgeService.purgeBatch` — 성공한 id만 삭제 |
| 배치 루프 종료 조건 교체 | `while (batchCount > 0)` — 옛 조건(`== batchSize`)은 건너뛴 사용자가 계속 후보로 잡혀 **무한 루프**가 된다 (회귀 테스트 있음) |

**남은 것 — 이미 생긴 고아 그래프.** 이번 수정은 새로 생기는 것을 막을 뿐이다. 기존 고아는
`projectId`를 알 방법이 없어(행이 이미 사라졌다) Neo4j의 `project_id` 목록과 RDB를 대조하는
별도 도구가 필요하다. 배포 전에 한 번 돌리면 된다.

**우선순위: 최상 — 완료.**

### 4-2. 연락처가 플레이스홀더다 — ✅ 완료

`contact@why-code.example`(수신 불가능한 예약 도메인)로 남아 있던 것을 **`contact@why-code.com`**
으로 교체했다. Cloudflare Email Routing으로 개인 메일함에 전달되며 실수신을 확인했다.

- [x] 실제 수신 가능한 주소로 교체 — `LegalLayout.tsx`의 상수 하나를 4개 화면(약관 한/영,
      개인정보처리방침 한/영)이 참조한다
- [ ] **발신은 아직 안 된다** — Email Routing은 전달 전용이라 그 주소로 답장할 수 없다.
      5-3(이용자 통지 수단)과 함께 볼 것

### 4-3. 운영 주체 표시가 자리표시자다

**표시 자체는 있다** — `LEGAL_OPERATOR`가 "whycode 팀"으로 렌더된다. 상수 주석도
"배포 전 실제 값으로 교체할 자리"라고 명시하고 있다. 즉 없는 게 아니라 **정할 것이 남았다.**

- [ ] 개인·팀 무상 운영이면 현행 유지 가능. 다만 국내 이용자를 받으면 **개인정보 보호책임자
      (이름·연락처)** 표시가 필요하다
- [ ] 사업자로 운영한다면 상호·대표자·주소·사업자등록번호를 채운다

### 4-4. 동의를 받지도, 기록하지도 않는다

로그인 화면에 약관 동의 문구가 없고(약관 링크는 랜딩 푸터에만 있다), `users` 테이블에 동의 시각·
버전 컬럼이 없다([V1 migration](../services/backend/src/main/resources/db/migration/V1__create_auth_and_github_foundation.sql)).
동의를 받았다는 증거가 남지 않는다.

- [ ] 가입 시 동의 UI를 둔다
- [ ] 동의 시각·약관 버전을 컬럼으로 남긴다 (migration 필요)

### 4-5. 제3자 요청 창구가 Jira에만 있고, 내보내기가 없다

수집 대상이지만 우리 가입자가 아닌 사람들의 이름·이메일이 그래프에 남는다. Jira는 보고·삭제가
자동화돼 있지만 **나머지 6개 소스에는 동등한 열람·삭제 요청 경로가 없다.**

이용자 본인의 **열람·내보내기 API도 없다.** 지울 수는 있지만 가져갈 수는 없다.

- [ ] 소스와 무관하게 동작하는 요청 접수·처리 절차를 만든다 (사람이 처리하는 경로라도 명시)
- [ ] 프로젝트 단위 내보내기(대화·그래프 요약)를 만든다 — 이동권 대응이자 이탈 장벽 완화

---

## 5층 — 운영 장치가 없다

혼자 쓸 때는 문제가 생기면 본인이 안다. 남이 쓰기 시작하면 **사용자가 먼저 알고 운영자가 나중에 안다.**

### 5-1. 모니터링·알림이 없다

actuator·micrometer·에러 트래킹 의존성이 아예 없고 `/health`만 있다. 수집이 멈춰도, 큐가 쌓여도,
DLQ로 빠져도 알림이 오지 않는다.

- [ ] 최소한 DLQ 적재·수집 실패·컨테이너 재시작에 알림을 붙인다

### 5-2. 백업이 호스트 로컬에만 있다

**정기 실행은 켜져 있다**(cron + 보관 기간 정리). 남은 것은 **어디에 두느냐**다 — 덤프를 같은
호스트에 남기므로 호스트가 통째로 죽으면 백업도 함께 사라진다
([deployment.md §4-5](deployment.md)). PostgreSQL 쪽은 재생성이 불가능하다(전 사용자 재연동).

Neo4j 백업은 Community 제약상 `stop → dump → start`라 매번 짧은 전면 중단이 따른다
([backup.sh](../infra/scripts/backup.sh)). 혼자 쓸 때는 문제가 아니지만, 이용자가 있으면
그 시간대가 곧 장애 시간이 된다.

- [ ] 오프사이트 복사(객체 스토리지 등)를 붙인다
- [ ] 복원을 실제로 한 번 해본다 — 스크립트 주석도 "복원해 본 적 없는 백업은 백업이 아니다"라고 적고 있다
- [ ] Neo4j 중단 시간대를 공지 가능한 시간으로 고정할지 정한다

### 5-3. 이용자에게 연락할 수단이 없다

메일 발송 설정이 없다. 장애 공지, 약관 변경 통지(약관은 7일 전 고지를 약속한다), 탈퇴·파기
안내를 보낼 방법이 없다.

- [ ] 발송 경로를 정한다 (약관이 이미 통지를 약속하고 있다)

### 5-4. 큰 webhook이 조용히 거부될 수 있다

nginx에 `client_max_body_size`가 없어 기본 1MB가 걸린다. GitHub webhook 페이로드는 그보다 훨씬
클 수 있고(커밋이 많은 push), 넘으면 413으로 잘린다 — **수집이 멈춘 줄 모르고 지나가는 실패**다.

- [ ] `client_max_body_size`를 webhook 경로에 맞게 올린다

---

## 6층 — 규모가 커지면 깨진다

유일하게 **나중에 해도 되는** 층이다. 다만 "서버를 늘려서 버티자"는 선택지가 지금은 막혀 있다는
것은 알고 시작해야 한다.

### 6-1. 인메모리 상태 때문에 서버를 늘릴 수 없다

프로세스 안에만 사는 상태가 네 곳이다.

| 위치 | 무엇 |
|---|---|
| `graph/postprocess.py` | Layer 4 빌드 상태·dirty 플래그 (ai-engine CLAUDE.md에 교체 필요가 예고돼 있다) |
| `pipeline_worker/webhook/ProjectCollectionSerializer` | 프로젝트 단위 수집 직렬화 락 |
| `conversation/service/SummaryBackoffTracker` | 요약 실패 백오프 |
| `graph/project_context.py` `_summary_cache` | 프로젝트 컨텍스트 캐시 |

인스턴스를 늘리는 순간 **같은 프로젝트를 둘이 동시에 수집**할 수 있다.

- [ ] 수평 확장 시점에 공유 저장소(Redis·DB) 기반 락·상태로 교체한다

### 6-2. 스케줄러에 분산 락이 없다

`UserPurgeScheduler`(탈퇴 파기)와 `JiraPersonalDataReportScheduler`(개인정보 보고)가
`@Scheduled`로만 돈다. 인스턴스를 늘리면 **파기와 대외 보고가 중복 실행**된다.

- [ ] 분산 락(ShedLock 등)을 붙인다

### 6-3. 처리량이 전역 고정값이다

수집 풀 3, webhook 풀 4로 고정돼 있고 provider별 rate limit과 OpenAI 페이싱도 프로세스 전역이다.
테넌트가 늘면 서로를 막는다. Neo4j Community는 클러스터 구성 자체가 불가능해 수직 확장만 남는다.

---

## 이미 되어 있는 것

공개 준비 = 전부 새로 만들기가 아니다.

- **그래프 프로젝트 격리** — `(project_id, 자연키)` 복합 유니크 (문서 맨 위 참고)
- **질의 스코프** — `/query`는 `project_id`로 전부 스코프, 인가는 backend 전담
- **계정 탈퇴 절차** — soft delete 후 30일 유예, 이후 배치가 RDB 정리
  (단 그래프·provider 권한이 빠져 있다 — 4-1)
- **약관·개인정보처리방침 본문** — 소스 8종별 수집 항목이 개별 고지돼 있고, **국외 이전
  (OpenAI L.L.C., 미국)과 위탁도 제4조에 명시**돼 있다. 한국어·영어 두 벌
  (`components/landing/legal/`)
- **Jira 개인정보 보고(PDR)** — 보고·갱신·삭제 사이클 구현 완료. 활성화와 봇 계정 동의만 남았다
- **DB 백업 정기 실행** — cron 자동화와 보관 기간 정리가 켜져 있다
- **입력 크기 제한** — 대화·메시지 요청에 `@Size` 상한이 걸려 있어, 긴 입력으로 LLM 비용을
  부풀리는 경로는 막혀 있다
- **프로덕션 기동 가드** — `./prod.sh`가 시크릿 미설정 시 기동 거부(fail-closed), 외부에 여는
  포트 0, 자원 상한·로그 로테이션

---

## 공개 범위별 필요 항목

"지인 시연"은 Cloudflare Access로 사람을 이메일 단위로 제한해 두는 것을 전제로 한다 —
지금은 도메인을 아는 누구나 로그인 화면까지 닿는다([deployment.md §5](deployment.md)).

| 항목 | 지인 시연 | 제한 공개 (수십 명) | 완전 공개 |
|---|---|---|---|
| ~~4-1 탈퇴 시 파기 누락~~ | ✅ 완료 | ✅ 완료 | ✅ 완료 |
| 0-1 조직 레포 | 불필요 | **필수** | **필수** |
| 0-2 팀 공유 | 불필요 | 권장 | **필수** |
| 0-3 Slack 승인 | 불필요 | 범위에서 빼도 됨 | **필수** |
| ~~0-4 운영자 PAT 제거~~ | ✅ 완료 | ✅ 완료 | ✅ 완료 |
| 1층 비용·남용 | 불필요 | 최소 상한 | **필수** |
| 2-1·2-2 인증 구멍 | 권장 | **필수** | **필수** |
| 2-3 세션 탈취 방어 | 불필요 | 권장 | **필수** |
| 3층 외부 앱 승인 | 불필요 | 쓰는 것만 | **전부** |
| 4층 나머지(법적) | 불필요 | **필수** | **필수** |
| 5층 운영 장치 | 불필요 | 권장 | **필수** |
| 6층 확장 | 불필요 | 불필요 | **필수** |

---

## 열린 결정

**공개 범위를 어디까지 볼 것인가.** 이 답에 따라 해야 할 일의 양이 크게 달라진다.

- **지인 시연** — 4-1이 완료됐으므로 지금 상태로 배포 가능하다 (Access로 이메일 제한 전제)
- **제한 공개** — 0층과 최소한의 가입 제한, 4층 법적 항목이 먼저다
- **완전 공개** — 전 층위 + OpenAI 비용 구조에 대한 답이 필요하다

범위가 정해지면 그 범위에서 **배포 전에 꼭 해야 할 것만** 추려 순서를 잡는다.
