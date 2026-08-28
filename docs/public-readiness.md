# 공개 전환 점검표 (불특정 다수 대상)

> 지금의 배포를 **불특정 다수가 쓰는 서비스**로 바꾸려면 무엇을 고쳐야 하는지를, 막히는 순서대로 모은다.
> 배포 절차 자체는 [deployment.md](deployment.md), 배포 경로의 미완 항목은
> [deployment-followups.md](deployment-followups.md)를 본다 — 이 문서는 그 둘이 다루지 않는
> "여러 사람이 쓴다"는 조건에서만 생기는 것들이다.
>
> 근거는 실제 코드·설정에서 확인했다(최초 작성은 `develop` 7b477c4 기준).
> ✅ 표시된 항목은 `feat/multi-user`에서 수정을 마쳤고, 그 절의 서술은 **수정 전 상태를 설명한 뒤
> 무엇을 바꿨는지 잇는** 형태다. 미완료 항목의 서술만 현재 상태로 읽으면 된다.

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
| **0층** | 기능이 아예 막힌다 | 4 (**2 완료**, 1 범위 밖) | 코드 변경 |
| **1층** | 비용·쿼터를 전원이 공유한다 | 3 | 코드 변경 + 정책 |
| **2층** | 인증·시크릿·세션에 구멍이 있다 | 4 (**일부 완료**) | 코드 변경 |
| **3층** | 외부 앱이 공개 배포 상태가 아니다 | 9종 (**1 완료**) | **외부 심사 대기** |
| **4층** | 법적 고지·동의·파기가 비어 있다 | 6 (**2 완료**) | 정책 + 코드 변경 |
| **5층** | 운영 장치가 없다 | 4 (**1 완료**) | 인프라 |
| **6층** | 규모가 커지면 깨진다 | 3 | 구조 변경 (나중) |

**완료**: M0 = 4-1(탈퇴 시 파기 누락) · M1a = 0-4(운영자 PAT) · M1b = 0-1(조직 레포) ·
4-2(연락처) · 2-1·2-2(인증 구멍) · 2-3 일부(보안 헤더) · 4-4(동의 기록) · 0-1c(파기 폐기 가드) ·
5-4(webhook 본문 상한) · 3층 GitHub App(이미 Public 확인).

**다음**: 0-1b(사용자 GitHub 토큰 저장, Critical — GitHub App 설정 확인이 선행돼야 함) ·
0-3 Slack 결정. 아래 「다음 순서」 참고(일부 낡음 — 위 완료 목록이 최신 기준).

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

### 0-1b. 조직 설치에서 남의 레포까지 보이고 연동된다 (0-1의 후속)

**0-1로 조직 설치를 열면서 새로 생긴 구멍이다.** PR #121 봇 리뷰에서 발견했다.

레포 목록은 **installation token**으로 `GET /installation/repositories`를 부른다
(`GitHubInstallationService.findRepositories`). 이 엔드포인트는 **설치가 가진 전체 레포**를
돌려주며 사용자 범위가 아니다.

설치가 개인 계정 전용이던 시절에는 "설치의 레포 = 내 레포"라 문제가 아니었다.
**조직 설치를 허용하는 순간 "설치의 권한 ≠ 사용자의 권한"이 된다.**

```
조직에 App 설치(All repositories) → 구성원 A는 repo-1만 접근 가능
  → 우리 화면에는 repo-1..N 전부 표시
  → A가 repo-2를 연동 → installation token으로 repo-2 전체 히스토리 수집
  → A가 질의로 그 내용을 읽는다
```

레포 이름 노출에 그치지 않고 **접근 권한이 없는 저장소의 커밋·이슈·PR 본문을 수집해 읽게 된다.**
`.claude/review-severity.md` 기준으로 **Critical**(사용자 데이터·보안)이다.

#### 해결 방식 — 사용자 GitHub 토큰을 저장한다

"이 사용자가 이 설치에서 무엇을 볼 수 있나"는 **사용자 access token**으로만 물을 수 있다
(`GET /user/installations/{id}/repositories`). 그런데 우리는 그 토큰을 **로그인 때 쓰고 버린다**
(`users` 테이블에 토큰 컬럼이 없다).

검토한 두 안 중 **B를 채택한다.**

| | 방식 | 문제 |
|---|---|---|
| A | 로그인 때 "볼 수 있는 레포 id"를 저장해 목록에서 거른다 | 로그인 사이에 낡는다 — 새 접근 권한을 받으면 재로그인해야 보인다. 결국 B로 다시 만들게 된다 |
| **B** | **사용자 토큰을 암호화 저장하고 조회 시점에 GitHub에 묻는다** | 자격증명이 하나 늘고 만료·갱신 처리가 붙는다 |

**B가 이 레포의 기존 패턴이다.** Slack·Jira·Google Chat·Linear·Asana·ClickUp·Notion은 전부
사용자 OAuth 자격증명을 암호화 저장한다. GitHub만 App(installation) 방식이라 예외였고, 조직 설치를
열면서 나머지와 같은 모양이 필요해졌다. 암호화(`credentialCryptoService`)·갱신
(`AccessTokenRefresher`)·폐기(`ProviderCredentialLifecycle`) 기계장치는 이미 있다.

- [ ] **선행 확인** — GitHub App 설정의 "Expire user authorization tokens"가 켜져 있는지.
      켜져 있으면 사용자 토큰이 **8시간** 만료라 refresh 토큰(6개월) 갱신 구조가 필요하고,
      꺼져 있으면 저장만 하면 된다. **이 답이 작업량을 크게 가른다**
- [ ] 저장 위치 — 기존 자격증명은 `integrations`의 **(프로젝트, provider)** 단위인데 GitHub 사용자
      토큰은 **사용자 단위**다(프로젝트가 없어도 존재). 새 테이블이 필요하다
- [ ] `findRepositories`가 사용자 토큰으로 조회하도록 교체
- [ ] 개인정보처리방침 `#github` 절에 저장 항목 추가 — 새 자격증명을 저장하게 된다
- [ ] 연동 시점 검증도 함께 볼 것 — 목록만 거르고 연동을 안 막으면 우회할 수 있다

**우선순위: 최상(공개 전 필수) — 후속 PR.** 지금 사용자가 둘뿐이고 테스트 조직도 본인 소유라
실제 노출은 없지만, **공개 전에는 반드시 닫아야 한다.**

> **PR #121은 이 항목을 남긴 채 머지한다(2026-08-27 결정).** 봇 리뷰가 Critical로 판정했고
> 그 판정은 옳다 — 다만 조직 설치를 여는 PR에서 사용자 토큰 저장까지 함께 하면 범위가 두 배가
> 되고, 실제 노출이 아직 없어 후속 PR로 나눴다. **이 항목이 닫히기 전에는 조직 소속이 섞인
> 사용자를 받으면 안 된다.**

### 0-1c. 파기의 provider 권한 폐기 가드가 실제로는 동작하지 않는다 (0-1의 후속) — ✅ 수정 완료

> **2026-08-28 수정.** 7개 provider client·어댑터·`IntegrationRevocationService`를 전부
> `boolean`으로 바꿔 실패 신호가 끊기지 않게 했다. backend 전체 테스트 통과. 상세는 이 절 끝의
> 「수정 내용」 참고.

PR #121 3차 봇 리뷰에서 발견했다. 파기에서 폐기 실패를 잡으려고 `revokeAll`을 `boolean`으로
바꿨는데(1차 리뷰 반영), **그 아래에서 이미 예외를 삼키고 있어 가드가 한 번도 발동하지 않았다.**

```java
// SlackClient.java:98 — 다른 provider도 같은 모양
} catch (RestClientException exception) {
    log.warn("Slack token revoke request failed. error={}", exception.getMessage());
}   // ← 여기서 삼킨다. revoke()는 절대 던지지 않는다
```

그리고 이건 **의도된 설계**였다 — `backend/CLAUDE.md`가 "폐기 실패는 각 client가 로그만 남기고
삼킨다 — 이미 폐기된 토큰이나 provider 장애로 해제가 막히면 사용자가 데이터를 지울 방법을 잃는다"고
적고 있었다. **연동 해제(사용자 대면)에는 옳지만, 파기에는 맞지 않았다.**

- [x] `ProviderCredentialLifecycle.revoke`가 성공·실패를 신고하게 바꿨다 — **구현체 7종
      (Slack·Jira·Discord·Google Chat·Linear·Asana·Notion) 전부**
- [x] 해제 경로는 지금 동작(실패해도 진행)을 유지하고, **파기 경로만** 실패에 반응하게 했다
- [x] **실기동 검증** — 로컬 스택에서 Slack 연동을 해제했을 때 provider가 `token_revoked`(이미
      폐기된 토큰)로 응답 → `SlackClient`가 이를 실패로 감지해 로그로 남기고(`Slack token revoke
      failed. error=token_revoked`) → 그럼에도 `disconnect`는 반환값을 무시하고 정상 진행해
      연동이 화면에서 정상적으로 사라짐을 확인. Jira·Google Chat·Notion·GitHub도 함께 확인 —
      전부 정상 해제됨(로그가 없는 것 자체가 성공 신호 — 실패했을 때만 warn을 남기도록 만들었다)

#### 수정 내용

| 무엇 | 어디 |
|---|---|
| 인터페이스 계약 변경 | `ProviderCredentialLifecycle.revoke` — `void` → `boolean` |
| 7개 client 반환 타입 변경 | `SlackClient`·`JiraOAuthClient`·`DiscordClient`(2개 메서드)·`GoogleChatClient`·`LinearOAuthClient`·`AsanaOAuthClient`·`NotionClient` — 전부 `RestClientException` catch 시 `false`, 정상 시 `true`. Slack만 HTTP 200이라도 `ok:false`면 `false` |
| Discord AND 결합 | `DiscordCredentialLifecycle.revoke` — 토큰 폐기·봇 길드 퇴장을 각각 지역 변수에 담아 실행한 뒤 AND(`&&`를 호출식에 직접 쓰면 short-circuit으로 두 번째 호출이 아예 안 나가는 버그가 생긴다) |
| 상위 서비스 | `IntegrationRevocationService.revoke`가 `find(...).map(...).orElse(true)`로 boolean 반환, `revokeAll`이 이 반환값을 주 실패 판단으로 사용 |

backend 테스트 통과(신규 케이스 다수 — Discord short-circuit 방지, Slack `ok:false` 감지를 각각
직접 겨냥한 테스트 포함).

**검증 중 발견한 별개 문제 (이번 PR 범위 밖)**: 실기동 중 로컬 DB의 한 Jira 연동에서 자격증명
복호화가 `JsonParseException`으로 실패해 `disconnect`가 500을 내는 걸 발견했다. 조사 결과 이건
이번 변경과 무관한 **기존 버그**다 — `IntegrationRevocationService.revoke(Integration)`는 이번
변경 전에도 `ifPresent`의 람다 안에서 예외가 나면 그대로 전파했으므로(`void`든 `boolean`이든
전파 동작은 동일), 자격증명이 파싱 불가능한 형식(레거시 `email:token` 평문으로 추정)으로
저장돼 있으면 disconnect가 예전부터 500이 났을 것이다. **문서화하지 않기로 결정**(로컬 개발
DB의 레거시 테스트 데이터 문제로 판단, 별도 대응 없음).

#### PR #125 봇 리뷰 Major 대응 (2026-08-28)

봇이 지적: "영구 실패(Slack `token_revoked`, 무효 토큰 401, Discord 길드 404)에서 `revokeAll`이
매 회차 `false`를 반환해 탈퇴 사용자 행이 영영 삭제되지 않는다." 코드로 재현해 확인 —
`UserPurgeService`의 실패 추적(`excludedIds`)이 cron 실행 1회 안에서만 유지되는 로컬 변수라
재시도 횟수 제한이 전혀 없었다. 두 안전장치를 함께 추가했다.

- [x] **(B) Slack의 "이미 무효" 응답을 성공으로 재해석** — RFC 7009(OAuth 표준)는 이미 무효한
      토큰도 HTTP 200을 요구하는데, Slack만 이를 안 따르고 `ok:false` + `error` 필드로 알린다.
      공식 문서로 확인한 3개 값(`invalid_auth`·`token_revoked`·`token_expired`)은 "지울 대상이
      이미 없다"는 뜻이므로 성공 취급. 나머지 6개 provider는 표준을 따를 것으로 보이고
      실기동에서도 실패가 관측된 적 없어 건드리지 않았다(확인 안 된 것을 가정으로 코드에 안 박음).
- [x] **(A) 파기 강제 진행 안전판** — `User.deletedAt`(새 컬럼 없이 기존 필드 재활용)으로
      "`gracePeriod`(30일) + `forcePurgeAfter`(7일)"가 지나도록 계속 실패해온 사용자는 강제로
      삭제를 진행한다. cron이 하루 1번만 돌므로 이 기간 경과가 곧 "최소 7번 연속 실패"라는 뜻이다.
      강제 삭제는 `log.error`로 남긴다(provider grant가 안 지워진 채 계정이 사라지는 것이므로).
- [x] 리뷰 중 직접 발견한 별개 결함도 함께 고쳤다 — `Set.of(...).contains(null)`이 `false`가
      아니라 `NullPointerException`을 던진다는 사실을 실제로 재현해 확인. Slack이 `ok:false`인데
      `error` 필드를 생략하면(이론상 가능) 이 NPE가 `disconnect`까지 전파돼 500이 날 수 있었다.

**우선순위: 상 — 완료.**

### 0-2. 팀이 프로젝트를 함께 볼 수 없다

프로젝트는 **소유자 1명**에게만 속한다. 목록·조회·삭제가 전부 `owner_id` 스코프이고
([Project.java:34](../services/backend/src/main/java/com/history/backend/project/domain/Project.java),
`ProjectService`의 모든 조회), 팀원 초대나 공유 개념이 없다.

연동 유니크 키가 `(project, provider)`라
([V3__create_integrations.sql:28](../services/backend/src/main/resources/db/migration/V3__create_integrations.sql))
**팀원마다 같은 레포를 각자 연동하는 것은 막히지 않는다.** 그러면 같은 레포를 N번 수집해
수집 비용도 그래프도 N벌이 되고, 그 N벌은 서로 다른 프로젝트라 답변도 갈라진다.

팀의 의사결정 맥락을 다루는 제품인데 정작 그 맥락을 팀이 나눠 볼 수 없다는 것이 문제의 본질이다.

- [x] **프로젝트 멤버십 도입 여부 결정 — 도입하지 않는다.** 공개 범위를 "여러 사람이 각자
      개인 용도로 쓴다"로 정했다(2026-08-26). 한 프로젝트를 팀이 나눠 보는 기능은 범위 밖이다
- [ ] **같은 레포가 이미 다른 프로젝트에 연결돼 있음**을 감지해 안내한다 — 팀 공유를 안 하기로
      한 이상 중복 연동은 막히지 않으므로, 이건 기능 문제가 아니라 **비용 문제**로 1층에 남는다

**우선순위: 범위 밖(멤버십) + 중(중복 감지).** 팀 단위 제품으로 방향을 바꾸면 이 항목이 되살아난다.

### 0-3. Slack은 공개 배포를 켜는 순간 느려진다

**지금은 문제가 없다.** 우리 Slack 앱은 공개 배포 전이라 `conversations.history`·`replies`가
Tier 3(**50+ req/min · 최대 1,000건**)로 동작한다. "Slack이 지금 느리다"는 서술은 틀렸다.

**공개 배포를 켜는 순간** 그 한도가 **1 req/min · 최대 15건**으로 떨어진다. 한도는
`per API method per workspace/team per app`이라 **토큰 종류(user/bot)를 가리지 않고**,
워크스페이스별로 버킷이 갈린다(같은 워크스페이스의 여러 사용자는 한 버킷을 공유한다).

메시지 1만 건이면 시간당 900건 기준 **약 11시간**이다. 증분 수집은 가볍다.

#### Marketplace 면제 경로는 구조적으로 막혀 있다

승인받으면 한도가 복구되지만, 우리 제품 형태가 등재 기준에 정면으로 걸린다.

| 기준 | 우리 상태 |
|---|---|
| **"do not include functionality in Slack"** (거부 사유) | ❌ Slack 안에 슬래시 커맨드·봇 메시지·App Home이 **하나도 없다**. 데이터만 가져가고 돌려주는 것이 없다 |
| **"export or backup message data"** (거부 사유) | ⚠️ 메시지 본문을 Neo4j `Communication.body`에 저장한다 |
| user token `*:history` scope | ⚠️ `channels:history`·`groups:history` 사용. 지침이 명시적으로 지양하나 "Real-time Search" 같은 명확한 사용 사례는 예외로 인정한다 |
| 활성 워크스페이스 **5곳** + 주간 활성 **10명** | ❌ 현재 1곳. **샌드박스는 제외**된다고 명시 |
| `app_uninstalled` 처리 | ❌ 없음 |
| 심사 기간 | 예비 **최대 10영업일**(반려 시 큐 리셋) + 기능 **최대 10주**. 단축·생략 불가 |

즉 **숫자를 채워도 통과할 수 없다.** "Slack 안에 기능이 없다"는 설정이 아니라 제품 구조다.

#### 선택지

| | 내용 | 속도 | 비용 |
|---|---|---|---|
| **A. Slack 후순위** | 초기 공개에서 빼거나 "곧 지원" 표시 | — | 대화 소스는 Discord·Google Chat이 대체 |
| **B. 느린 채로 연다** | 배포하고 수집 범위 축소(기간·채널 수 제한) + "초기 동기화가 오래 걸림" 명시 | 1/min | **유료 구독을 붙이면 약관 마찰이 확정된다** — 아래 참고 |
| **C. BYO 앱 병기** | 고객이 자기 워크스페이스에 앱을 만들어 토큰을 입력 | **50/min** | 온보딩 5단계, **설계 원칙(토큰 직접 입력 금지) 예외**, 해제 시 우리가 폐기 불가, "내부 앱" 해석 위험 |
| **D. 등재 추진** | Slack 내 기능(예: `/why` 검색) 신설 → 5곳·10명 확보 → 심사 | 50/min | 제품 로드맵 수준. 3~4개월 + 팀 확보 기간 |

C의 근거는 changelog의 **"Internal customer-built applications are not impacted by these changes"** 이다.
고객이 만든 앱은 내부 앱이라 새 한도 대상이 아니다.

**유료 구독 결정(2026-08-26)이 B의 평가를 낮춘다.** 무상일 때는 "commercially distributing"에
해당하는지 해석의 여지가 있었지만, 구독 결제를 받으면 **명백히 상업적 배포**가 되어
"Marketplace가 상업적 배포의 유일한 적절한 채널"이라는 약관 조항에 정면으로 걸린다.
결제가 붙기 전까지는 B도 선택지지만, **유료화와 함께 A 또는 C로 옮겨야 한다.**

- [ ] **A~D 중 결정한다.** 현재 유력안은 **A + C 병기** — 기본은 OAuth 버튼(느림), 빠른 수집을
      원하면 직접 앱을 만들어 연결. 사용자에게 제시하는 이유는 "봇이 싫으면"이 아니라
      **"빠르게 받고 싶으면"** 이어야 정확하다(속도를 가르는 건 봇 여부가 아니라 우리 앱이냐 고객 앱이냐다)
- [ ] C로 간다면 **Slack에 문의해 "고객이 만든 앱의 토큰을 외부 서비스가 쓰는 것"이 내부 앱
      예외에 해당하는지 확답**을 받는다 — 되돌리기 비싼 작업이다
- [ ] 봇 토큰 전환은 **별개 항목**으로 둔다. 최소 권한이라는 장점은 있지만 rate limit도 등재 자격도
      바꾸지 못하고, 채널마다 초대·관리자 승인이 필요해 **개인 사용자에게는 오히려 불리**하다

**우선순위: 중.** 대화 소스에 대안이 있어 Slack이 막혀도 제품이 성립한다.

**근거**: [rate limits](https://docs.slack.dev/apis/web-api/rate-limits/) ·
[2025-05-29 changelog](https://docs.slack.dev/changelog/2025/05/29/rate-limit-changes-for-non-marketplace-apps) ·
[Marketplace 가이드라인](https://docs.slack.dev/slack-marketplace/slack-marketplace-app-guidelines-and-requirements/) ·
[review guide](https://docs.slack.dev/slack-marketplace/slack-marketplace-review-guide/)

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

**요금 방향은 정해졌다(2026-08-26)** — **무료 일부 제공 + 유료 구독으로 전 기능**. 다만
**결제 구현은 후순위**로 미뤘다. 그래서 이 층의 당면 과제는 "결제 붙이기"가 아니라
**무료 한도를 코드로 강제하기**다. 결제가 붙기 전까지는 그 한도가 곧 전체 한도가 된다.

- [ ] **원가 측정이 먼저다** — 프로젝트 1개 수집·질의에 OpenAI 비용이 얼마인지 모르면 무료 한도를
      정할 수 없다. 비용이 **초기 수집에 몰려 있어**(전체 히스토리 diff 요약 + 임베딩) 가입만 하고
      이탈해도 이미 지출된다. 질의 쪽은 `/query`의 `include_debug`로 토큰 usage를 뽑는 경로가
      eval용으로 있으나, 수집 경로 계측은 확인이 필요하다
- [ ] 사용자별 사용량 테이블 + 일일 질의·토큰 예산
- [ ] 프로젝트 수 상한, 수집 규모 상한(커밋·채널 수) — 무료 티어를 **수집 규모로 끊을지 질의
      횟수로 끊을지**가 실질적 설계 문제다
- [ ] 결제 전까지의 가입 게이트(초대코드·웨이팅)를 둘지 정한다

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
Google Chat 쿼터는 Cloud 프로젝트당이라 마찬가지다. 한 사용자의 대량 수집이 다른 사용자를 막는다.

(**정정**: 초판에는 "GitHub PAT도 시간당 한도가 하나다"라고 적었으나, M1a에서 운영자 PAT를
제거해 더 이상 해당하지 않는다. GitHub은 installation token이라 설치별로 갈린다.
Slack도 앱×워크스페이스 단위라 여기 해당하지 않는다 — 0-3 참고.)

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

- [x] `INTERNAL_SERVICE_TOKEN` 헤더 검증을 pipeline-worker 인바운드에 적용했다 —
      `security/InternalServiceAuthenticationFilter`. backend와 같은 규약(timing-safe 비교,
      토큰 미설정 시 기동 거부). Spring Security가 없어 `OncePerRequestFilter`를 `@Component`로 등록
- [x] **webhook 경로는 예외로 뒀다** — GitHub 서버가 직접 부르므로 우리 헤더를 붙일 수 없다.
      그 경로는 HMAC 서명 검증(fail-closed)이 이미 지킨다. 이 예외를 "구멍"으로 오인해 막으면
      webhook 수집이 끊기므로 필터 주석과 `pipeline-worker/CLAUDE.md`에 이유를 남겼다
- [x] backend `PipelineWorkerClient`가 헤더를 보낸다 — **이 호출은 실패를 삼키므로**(연동 롤백 방지)
      헤더를 빠뜨리면 401이 로그로만 남고 초기 수집이 조용히 멈춘다. 테스트가 헤더 전송을 검증한다

PR #122 봇 리뷰에서 pipeline-worker 필터가 **percent-encoding으로 우회**됨을 발견해 고쳤다
(`getRequestURI()`는 디코딩 전 원문인데 Spring MVC는 디코딩된 경로로 라우팅한다 —
`/api/v1/%63ollect/`가 필터는 통과하고 컨트롤러에는 도달했다). `UrlPathHelper`로 교체.

- [x] **backend의 기존 `InternalServiceAuthenticationFilter`(`/api/v1/internal/` 보호)도
      같은 결함이 있었다** — 같은 패턴(`getRequestURI().startsWith(...)`)을 그대로 썼다.
      pipeline-worker와 같은 방식(`UrlPathHelper`)으로 후속 PR에서 고쳤다

**우선순위: 상 — 완료.**

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

- [x] 내부 서비스 토큰 검증을 붙였다 — **4개 라우터 전부**(`main.py`의 `include_router`에
      `dependencies=[Depends(verify_internal_token)]`). `/health`만 열려 있다(`@app.get`이라 라우터 밖).
      토큰 미설정이면 lifespan에서 기동을 막는다
- [ ] 전 프로젝트 스코프로 도는 admin 엔드포인트를 훑어 `project_id` 필수화를 검토한다 —
      **별건으로 미뤘다.** `measurement.md` 런북이 **의도적으로 전역 호출**을 쓴다(eval용 전체 backfill).
      필수화하면 그 절차가 깨지므로 런북 재설계와 함께 판단한다. 인증이 붙어 "운영자만 부를 수 있다"가
      보장됐으므로 급하지 않다

#### 도입하면서 함께 고친 것

- **admin 라우터도 막아야 했다** — `AiEnginePrivacyClient`가 admin의 `/migrations/verify-actor-names`를
  부른다(개인정보 보고 스케줄러). "admin은 운영자 수동만"이라는 가정이 틀렸다
- **backend 클라이언트가 4개**였다(query·graph·actor·privacy). `AiEngineConfig`의 `defaultHeader`
  한 곳으로 전부 커버했다
- **`eval/runner.py`가 ai-engine을 직접 호출**한다 — `--token`(기본값 env `INTERNAL_SERVICE_TOKEN`)을
  추가했다. 없으면 기존대로 동작(무인증 엔진 대비)
- **`docs/measurement.md`의 admin 런북 curl 15개**에 `-H "$AUTH"`를 달았다. 빠뜨리면 eval 절차가 막힌다
- compose ai-engine 블록에 `INTERNAL_SERVICE_TOKEN`을 fail-closed(`:?`)로 추가

**우선순위: 상 — 완료.**

### 2-3. XSS 한 번에 세션이 통째로 넘어간다

세 가지가 겹친다.

1. access·refresh 토큰이 **`localStorage`**에 있어 스크립트로 읽힌다
   ([tokenStorage.ts:8-15](../clients/web-dashboard/src/auth/tokenStorage.ts))
2. ~~웹 서버가 보안 헤더를 하나도 보내지 않는다~~ → **해결됨**(아래 체크박스 참고)
3. refresh 토큰 회전에 **재사용 탐지가 없다** — 탈취된 토큰이 먼저 쓰여도 "Invalid"로 끝날 뿐
   다른 세션을 끊지 않는다 (`RefreshTokenService.rotateRefreshToken`)

혼자 쓸 때는 XSS를 심을 경로가 사실상 없지만, 공개하면 사용자 입력과 외부 데이터(커밋 메시지·
이슈 본문·Slack 대화)가 화면에 들어온다.

- [x] nginx에 보안 헤더를 추가했다 — CSP·X-Frame-Options·nosniff·Referrer-Policy.
      실응답과 브라우저 렌더 모두 확인. 빌드 산출물에 인라인 스크립트가 없어 `script-src 'self'`로
      조일 수 있었다(폰트 CDN 2곳과 인라인 **스타일**만 예외)
- [ ] refresh 토큰을 httpOnly 쿠키로 옮긴다
- [ ] 재사용이 감지되면 `revokeAllRefreshTokens`(이미 있는 메서드)로 전 세션을 끊는다

**우선순위: 상.** 남은 둘은 **로그인 응답 형태와 프론트 인터셉터를 함께 바꾸는 작업**이라
4-4(동의 UI)와 같은 파일을 건드린다. 순서를 겹치지 않게 잡는다.

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
| **Slack** | **등재는 구조적으로 막혀 있다** — 조사 결과 "Slack 안에 기능이 없는 앱"이 명시적 거부 사유다. 0-3에서 A~D 결정이 먼저 | **보류** |
| **Atlassian (Jira)** | 앱 Distribution 활성화 + 개인정보 보고(PDR) 의무 이행. 구현은 끝나 있고 `ATLASSIAN_PDR_ENABLED`가 기본 false, 봇 계정 동의(최초 1회)가 미완이다 ([jira-personal-data-policy.md](jira-personal-data-policy.md)) | 중간 |
| **Discord** | 봇 Public 전환 + MESSAGE_CONTENT intent. 100서버 초과 시 앱 verification 별도 ([discord-integration.md:62](discord-integration.md)) | 중간 |
| **GitHub App** | ✅ **이미 Public이다** — 비인증 요청에 `github.com/apps/history-tracker`가 200을 반환해 확인(private이면 404). 조직 설치가 가능했던 이유이기도 하다 | 완료 |
| **Linear** | 앱 "Public" 토글 — 꺼져 있으면 authorize가 앱을 찾지 못한다 | 짧음 |
| **Notion** | Public connection 유지 확인 (이미 이 전제로 구현) | 짧음 |
| **Asana · ClickUp** | OAuth 앱 공개 등록 (Asana는 granular scope 등록이어야 한다) | 짧음 |
| **공통** | redirect URI 9종을 전부 배포 도메인으로 교체 — 하나라도 로컬 값이 남으면 그 provider의 연동만 조용히 깨진다 ([deployment.md §3-1](deployment.md)) | 짧음 |

- [ ] Google Chat 검증 착수 (임계경로 — 가장 먼저 시작한다)
- [x] GitHub App Public — 이미 되어 있음을 확인
- [ ] Atlassian Distribution + PDR 활성화(봇 계정 동의)
- [ ] Discord Public 전환
- [ ] 나머지 4종 공개 등록 + redirect URI 일괄 교체
- [ ] Slack은 0-3의 A~D 결정에 따른다 — **승인 착수는 지금 할 수 있는 일이 아니다**

**실제로 쓸 provider만 하면 된다.** 9종을 전부 열 이유는 없다 — 초기 공개에 넣을 소스를 먼저
정하고 그것만 심사를 밟는다.

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
- [x] 이미 고아가 된 그래프가 있는지 조사했다 — **0건**(2026-08-27). Neo4j의 `project_id` 7개가
      전부 `projects`에 존재한다. M0 이전에 파기된 사용자가 없었다는 뜻이라 정리 도구는 불필요하다.
      대조 방법: Neo4j `MATCH (n) WHERE n.project_id IS NOT NULL RETURN DISTINCT n.project_id`와
      Postgres `SELECT id FROM projects`를 비교
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

**고아 그래프 조사 결과 (2026-08-27): 0건.** 이번 수정은 새로 생기는 것을 막고, 기존에 생긴
것은 없었다. 다만 이 대조는 **배포 환경에서 한 번 더** 해야 한다 — 로컬과 배포는 별개 DB다.

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

**유료 구독을 붙이기로 하면서 이 항목이 "선택"에서 "필수"로 바뀌었다.** 무상 운영이면 현행
"whycode 팀" 표기로도 버틸 수 있지만, 결제를 받는 순간 전자상거래에 해당해 사업자 정보 표시가
따라온다.

- [ ] 국내 이용자를 받으면 **개인정보 보호책임자(이름·연락처)** 표시 — 무상이어도 필요하다
- [ ] **유료화 시점에** 상호·대표자·주소·사업자등록번호·통신판매업신고번호를 채운다.
      사업자등록·통신판매업 신고가 선행돼야 한다 (**결제가 후순위라 지금은 착수 대상이 아니다**)
- [ ] 구체 요건은 법률 검토가 필요하다 — 이 문서의 판단은 개발자 관점의 정리일 뿐이다

### 4-6. 약관이 "무상 제공"을 전제하고 있다 — 유료화 시 전면 개정

이용약관 두 곳이 무상을 전제한다. 특히 **면책 조항이 "무상으로 제공되는 서비스"임을 근거로
책임을 제한**하고 있어(`TermsBodyKo.tsx:152`), 유료 구독이 붙으면 그 근거가 사라진다.

> "서비스는 현재 개발·검증 단계이며 **무상으로 제공**됩니다" (`TermsBodyKo.tsx:83`)

- [ ] 유료화 시 약관 전면 개정 — 요금·결제·갱신·**환불/청약철회**·서비스 중단 시 처리
- [ ] 개정 시 **시행 7일 전 공지** 의무가 이미 약관에 있다(제11조) → 5-3(발신 수단)이 선행돼야 한다
- [ ] 한국어·영어 두 벌을 함께 고친다 (`legal/TermsBodyKo.tsx`·`TermsBodyEn.tsx`)

**우선순위: 결제와 같이 움직인다.** 결제가 후순위이므로 지금은 착수하지 않되,
**결제 작업을 시작할 때 같은 묶음으로 잡는다** — 약관 없이 결제만 붙이면 안 된다.

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

- [x] `client_max_body_size`를 webhook 경로에 맞게 올린다

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

(초판에는 `graph/project_context.py`의 `_summary_cache`도 있었으나 **M1a에서 파일째 삭제**돼 사라졌다.)

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

## 남은 항목과 시점

범위가 "개인용 다중 사용자 + 무료 일부/유료 구독"으로 정해져, 이제 갈리는 축은 공개 범위가 아니라
**무료 공개 시점에 필요한가 / 유료화 시점에 필요한가**다.

| 항목 | 무료 공개 전 | 유료화 시 | 비고 |
|---|---|---|---|
| ~~0-1 조직 레포~~ · ~~0-4 PAT~~ · ~~4-1 파기~~ · ~~4-2 연락처~~ | ✅ 완료 | — | |
| 0-2 팀 공유 | **범위 밖** | 범위 밖 | 팀 제품으로 선회하면 되살아난다 |
| 0-2 중복 연동 감지 | 권장 | **필수** | 비용에 직결 |
| 0-3 Slack | **결정 필요** | A 또는 C | B는 유료화와 함께 탈락 |
| 1층 비용 가드 | **필수** | **필수** | 무료 한도 = 코드로 강제 |
| 2-1·2-2 인증 구멍 | **필수** | **필수** | 다음 작업 |
| 2-3 세션(쿠키·재사용 탐지) | **필수** | **필수** | 헤더는 완료 |
| 2-4 시크릿 회전 | 권장 | **필수** | |
| 3층 외부 앱 | **쓰는 것만** | 쓰는 것만 | GitHub App 완료 |
| 4-3 운영 주체 | 보호책임자만 | **사업자 정보 필수** | |
| 4-4 동의 기록 | **필수** | **필수** | 다음 작업 |
| 4-5 제3자 창구·내보내기 | **필수** | **필수** | |
| 4-6 약관 개정 | — | **필수** | 결제와 한 묶음 |
| 5층 운영 장치 | 권장 | **필수** | 5-3 발신 수단은 4-6의 공지 의무와 연결 |
| 6층 확장 | 불필요 | 사용자 증가 후 | |

---

## 확정된 것 (2026-08-26)

| 결정 | 내용 |
|---|---|
| **대상** | 여러 사람이 **각자 개인 용도로** 쓰는 서비스. 한 프로젝트를 팀이 나눠 보는 기능은 만들지 않는다 |
| **요금** | **무료 일부 제공 + 유료 구독으로 전 기능** |
| **결제 구현** | **후순위** — 지금은 무료 한도를 코드로 강제하는 것까지만 |

이 결정들이 바꾸는 것:

- **0-2 팀 공유** → 범위 밖. 대신 같은 레포 **중복 연동이 비용 문제로 1층에 남는다**
- **1층** → "남용 방지"가 아니라 **요금제의 무료 경계 설계**가 된다. 원가 측정이 선행
- **4-3 운영 주체** → 유료화 시 사업자 정보 **필수**로 승격
- **4-6 약관 개정** → 새로 생긴 항목. 현재 약관이 "무상 제공"을 전제하고 면책 근거로 삼고 있다
- **0-3 Slack** → 유료화하면 "commercially distributing"이 확정돼 B안(느린 채로 배포)이 탈락

즉 "개인용"은 할 일이 줄어드는 결정이 **아니다.** 줄어드는 건 0-2 하나이고, 1층과 4층은 무거워진다.

## 다음 순서

**결제를 미뤘으므로 1층보다 인증·법적 항목이 앞선다.**

1. **2층 인증 구멍** — 2-1(pipeline-worker) + 2-2(ai-engine)를 한 묶음으로.
   서로 성격이 같고 다른 작업과 파일이 안 겹친다
2. **4-4 동의 기록** — migration + 가입 UI. 약관 본문은 무상 전제 그대로 두고 동의만 받는다
3. **2-3 세션 방어** — refresh 쿠키 전환 + 재사용 탐지. **2번과 같은 파일을 건드리므로 뒤에**
4. **0-3 Slack** — A~D 결정. 코드보다 결정이 먼저다
5. **1층 비용 가드** — 원가 측정 → 무료 한도 설계
6. **3층 외부 앱** — 실제로 쓸 provider만. Google Chat이 임계경로
7. **5층 운영** — 모니터링·오프사이트 백업·발신 수단
8. **결제 + 4-6 약관 개정 + 4-3 사업자 정보** — 한 묶음으로
9. **6층 확장** — 사용자가 실제로 늘어난 뒤

**미검증으로 남은 것**: 2계정 설치 공유(0-1), 고아 그래프 정리(4-1), 파기 시나리오 실동작.
