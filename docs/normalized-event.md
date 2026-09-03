# NormalizedEvent 계약 — pipeline-worker ↔ ai-engine

pipeline-worker가 발행하고 ai-engine이 소비하는 **유일한 수집 계약**이다.
새 데이터 소스(커넥터)를 추가할 때 이 문서가 체크리스트가 된다.

핵심 원칙: **이벤트는 소스가 아니라 `nodeType`으로 해석된다.** ai-engine의 분기는
`nodeType` 하나뿐이고(`graph/event_handler.py`), `source`는 노드에 기록되는 속성·삭제
스코프·Actor alias 접두일 뿐이다. 따라서 새 소스가 기존 `nodeType` 중 하나로 정규화되면
**ai-engine은 무변경**이다.

---

## 봉투(envelope)

```json
{
  "projectId": "UUID",
  "nodeType": "ChangeSet | PullRequest | Issue | Communication | Document",
  "source":   "GITHUB | JIRA | SLACK | LINEAR | ...",
  "occurredAt": "ISO-8601 Instant",
  "actor":  { "id": "", "name": "", "email": null, "bot": null },
  "properties": { },
  "refs": { }
}
```

정의는 `pipeline-worker`의 `dto/NormalizedEvent.java` · `dto/ActorDto.java`.

| 필드 | 필수 | 규칙 |
|------|------|------|
| `projectId` | ✅ | 프로젝트 UUID. **없으면 ai-engine이 이벤트를 버린다** — 프로젝트 스코프 없는 노드는 어떤 조회에도 속하지 못하고 자연키 충돌로 다른 프로젝트와 병합될 수 있다. |
| `nodeType` | ✅ | 아래 5종 중 하나. 알 수 없는 값은 경고 로그 후 폐기된다. |
| `source` | ✅ | **대문자** 소스 식별자. Actor alias 접두(`GITHUB:login`)와 소스별 그래프 삭제의 스코프 키를 겸한다. |
| `occurredAt` | ✅ | **이벤트의 실제 발생 시각**(수집 시각이 아니다). checkpoint 전진 기준이라 여기가 틀리면 데이터가 영구 누락된다. |
| `actor` | ✅ | 작성자. 소스별 ID 형식이 다르며 동일인 통합은 ai-engine이 한다. |
| `properties` | ✅ | `nodeType`별 스키마. 아래 참조. |
| `refs` | ✅ | 다른 시스템 참조. 없으면 빈 맵(`null` 금지). |

### actor

```java
record ActorDto(String id, String name, String email, Boolean bot)
```

- `id` — 소스별 사용자 ID (GitHub `login`, Jira `accountId`, Slack `userId`, Linear `botActor.id`/`creator.id`).
  `SOURCE:id`가 `ActorAlias`의 자연키가 되므로 **소스 안에서 안정적·고유**해야 한다.
  사람이 바꿀 수 있는 표시 이름을 id로 쓰면 안 된다.
- `name` — 표시 이름. 개인정보라 `ActorAlias.pd_name`에만 저장된다.
- `email` — 없으면 `null`. 동일인 판단의 가장 강한 신호라 수집 가능하면 채운다.
  단 **협업 툴 계정의 이메일만** 쓴다 (예: git config 이메일은 개인정보라 사용 금지).
- `bot` — 선택. 봇/앱 계정이면 `true`, 소스가 판정할 수 없으면 미설정(`null`)으로 둔다.
  `refs.assignees[].bot`도 같은 의미로 쓴다 — 담당자 각각의 봇 여부.

### source · 표기 규칙

| 계층 | 표기 | 예 |
|------|------|-----|
| RDB `integrations.provider`, HTTP 경로 | 소문자 kebab | `github`, `google-chat` |
| `NormalizedEvent.source`, Neo4j `source` 속성 | 대문자 snake | `GITHUB`, `GOOGLE_CHAT` |
| Actor alias 접두 | 대문자 snake + `:` | `GITHUB:se-zero` |
| RabbitMQ routing key | `event.` + 소문자 snake | `event.github`, `event.google_chat` |

큐 바인딩이 `event.#`라 **새 routing key는 브로커 설정 변경 없이 소비된다.**

---

## nodeType별 properties

값이 `null`이거나 키가 없으면 ai-engine이 빈 문자열로 대체한다(명시된 예외 제외).

### ChangeSet — 코드 변경 (자연키: `hash`)

| 키 | 타입 | 비고 |
|----|------|------|
| `hash` | string | 커밋 SHA. **자연키** |
| `message` | string | 커밋 메시지. 임베딩 대상 — 이슈·대화와 어휘가 맞는 텍스트라 시맨틱 링커의 비교 기준이 된다 |
| `files` | array | `{path, diff, additions, deletions}` 목록. 파일별로 LLM diff 요약 → 임베딩 → `File` 노드 + `MODIFIED` 엣지 |

`occurredAt`: 커밋 시각. merge commit은 커넥터에서 제외한다(맥락 노이즈).

### PullRequest — 변경 묶음 (자연키: `pr_number`)

| 키 | 타입 | 비고 |
|----|------|------|
| `pr_number` | number | **자연키** |
| `title` · `body` | string | |
| `state` | string | |
| `base_branch` | string | |
| `url` | string | |
| `created_at` | string | 생성 시각. `occurredAt`은 머지 시각이라 별도 보존 |

`occurredAt`: 머지 시각(없으면 생성 시각).

### Issue — 이슈/티켓 (자연키: `external_id` — source와 함께 유니크)

| 키 | 타입 | 비고 |
|----|------|------|
| `external_id` | string | **자연키. 없으면 ai-engine이 이벤트를 버린다.** 플랫폼 **불변 ID** (Jira issue id, Linear UUID, Asana gid 등). 그래프 MERGE 키는 `(project_id, source, external_id)` |
| `issue_key` | string \| 생략 | 사람용 표시 키 (Jira `HT-7`, Linear `ENG-42`, GitHub `#142`). 검색·표시·텍스트 링크 매칭용. 키가 없는 소스(Asana·monday)는 생략 |
| `title` · `body` | string | 합쳐서 임베딩된다 |
| `status` | string \| null | 워크플로 상태 **원문** (팀 어휘, 예: "배포 대기"). 표시·답변용 — 기계 판정에는 쓰지 않는다 |
| `status_category` | string | **필수.** 소스 중립 3값 `open \| in_progress \| closed`. 종료 판정·`closed_at` 유도는 이 축 하나로 한다. `closed`는 완료+취소를 포함한 "닫힘". 세분 신호가 없는 소스는 열린 이슈를 전부 `open`으로 둔다 |
| `issue_type` · `priority` | string | |
| `created_at` | string | |
| `closed_at` | string | **`status_category == closed`일 때만 넣는다.** 아래 3-상태 규약 참고 |

`occurredAt`: 최종 수정 시각(없으면 생성 시각).

**`closed_at` 3-상태 규약** — ai-engine이 `status_category`와 조합해 해석한다:

| `closed_at` | `status_category` | 결과 |
|-------------|----------|------|
| 있음 | `closed` | `closedAt` 기록 |
| 없음 | `closed` 아님 | `closedAt`을 **null로 클리어** (재오픈 처리) |
| 없음 | `closed` | 기존 `closedAt` **보존** (안전망) |

과거의 terminal 상태 문자열 집합(커넥터·ai-engine 양쪽 하드코딩)은 폐기됐다 — 종료 판정은
각 커넥터의 normalizer가 `status_category`를 채우면서 한 번만 내린다.

**Linear 매핑** (`source = "LINEAR"`) — Jira와 같은 Issue 스키마를 공유하며, 아래 필드만 소스별로 다르다.

| 정규화 키 | Linear 원본 필드 |
|-----------|------------------|
| `external_id` | `id` (UUID) |
| `issue_key` | `identifier` (예: `ENG-42`) |
| `status` | `state.name` (팀 커스텀 워크플로 이름 원문) |
| `status_category` | `state.type` — `backlog`\|`unstarted` → `open`, `started` → `in_progress`, `completed`\|`canceled` → `closed`, 그 외 알 수 없는 값은 방어적으로 `open` |
| `closed_at` | `status_category == closed`일 때만 `completedAt`(없으면 `canceledAt`)으로 채운다 |
| `priority` | `priorityLabel` 원문 (Urgent/High/Medium/Low) — `priority == 0`(No priority)이면 키 생략 |
| `actor` | `botActor`(워크플로 자동화가 만든 이슈)가 있으면 그 정보로 `bot=true`. 없고 `creator`가 있으면 그 정보로 `bot=creator.app`. 둘 다 없으면 전 필드 `null` |
| `refs.assignees` | Linear는 담당자가 단일 필드(`assignee`)라 배열로 감싸 스냅샷화한다. 없으면 빈 배열(키는 유지) |
| `refs.parentExternalId` / `parentIssueKey` | `parent.id` / `parent.identifier` |

`occurredAt`: `updatedAt`.

**Asana 매핑** (`source = "ASANA"`) — Jira와 같은 Issue 스키마를 공유하며, 아래 필드만 소스별로 다르다.

| 정규화 키 | Asana 원본 필드 |
|-----------|------------------|
| `external_id` | `gid` (불변) |
| `issue_key` | 생략 — Asana는 사람용 표시 키가 없다 |
| `status` | 미발행 — sections는 팀 자유 어휘라 워크플로 상태 원문이 없다 |
| `status_category` | `completed` — `true` → `closed`, `false` → `open`. 필드 부재 시 방어적으로 `open` (`in_progress` 없음) |
| `closed_at` | `status_category == closed`일 때만 `completed_at`으로 채운다 |
| `priority` | 생략 — Asana Issue 스키마에 우선순위 개념이 없다 |
| `actor` | `created_by` → `{id, name, email, bot: null}`. **Asana API에 봇/앱 구분 신호가 없어 `bot`은 항상 미설정(null)이다.** `created_by`가 없으면 전 필드 `null` |
| `refs.assignees` | Asana도 담당자가 단일 필드(`assignee`)라 배열로 감싸 스냅샷화한다. 없으면 빈 배열(키는 유지), 조회 실패 시 이벤트 자체를 발행하지 않는다(§담당자 해제 규약) |
| `refs.parentExternalId` | `parent.gid` — `parentIssueKey`는 발행하지 않는다(Asana에 표시 키가 없다) |

`occurredAt`: `modified_at`(`created_at`은 별도 property로 보존).

Asana에는 이슈 키가 없어 커밋·PR·Slack 텍스트의 태스크 참조는 `refs.issueExternalRefs`(아래 「refs — 교차 참조」)로 회복한다.

**ClickUp 매핑** (`source = "CLICKUP"`) — Jira와 같은 Issue 스키마를 공유하며, 아래 필드만 소스별로 다르다.

| 정규화 키 | ClickUp 원본 필드 |
|-----------|------------------|
| `external_id` | `id` (불변 task id) |
| `issue_key` | `custom_id` — 있을 때만 채운다. Custom Task ID는 ClickUp Business+ 이상 유료 요금제에서만 켤 수 있는 옵션이라 없는 워크스페이스가 많다 |
| `status` | `status.status` 원문 |
| `status_category` | `status.type` — `open` → `open`, `custom` → `in_progress`, `done`\|`closed` → `closed`, 그 외 알 수 없는 값·status 부재는 방어적으로 `open`. **`done`을 `closed`로 묶는 이유**: 이 서비스는 코드 변경 추적이 목적이라, Done 그룹 진입(작업 완료 — 태스크는 열어둔 채 남을 수 있다)이 곧 코드 작업 종료 신호다. `closed`(완전 종료)는 그 이후에 오는 부가 단계일 뿐이다 |
| `closed_at` | `status_category == closed`일 때만 `date_closed`(없으면 `date_done`)로 채운다. **ClickUp 날짜 필드는 전부 epoch ms 문자열**이라 ISO-8601로 변환해 발행한다(`created_at`도 동일 변환) |
| `priority` | `priority.priority` 원문 — 없으면 키 생략 |
| `actor` | `creator` → `{id, name(username), email, bot}`. `username`이 `"ClickBot"`(ClickUp 자동화 전용 프로필)이면 `bot=true`, 그 외에는 `bot=null`(ClickUp API에 별도 봇 플래그가 없다). `creator`가 없으면 전 필드 `null` |
| `refs.assignees` | `assignees` 배열을 그대로 스냅샷화한다 — Multiple Assignees ClickApp 활성 여부와 무관하게 API가 항상 배열로 반환한다. 없으면 빈 배열(키는 유지) |
| `refs.parentExternalId` | `parent`(문자열 task id) — `parentIssueKey`는 발행하지 않는다 |

`occurredAt`: `date_updated`.

ClickUp도 Custom Task ID가 없는 워크스페이스에서는 이슈 키가 없어, 공유 태스크 URL
(`app.clickup.com/t/{task_id}`)에서 태스크 참조를 추출해 `refs.issueExternalRefs`로 회복한다
(Asana에 이은 둘째 사례). Custom Task ID URL(`app.clickup.com/t/{team_id}/{ABC-123}`)에 담긴
키는 기존 이슈 키 정규식(`ISSUE_KEY`)이 커버한다.

**GitHub 매핑** (`source = "GITHUB"`) — GitHub 이슈는 대화가 아니라 **이슈 트래커 아키타입**이다
(예전엔 `Communication`으로 발행했다 — GitHub만 연결한 프로젝트에 Issue 노드가 0개가 되는 문제로 전환).
`GET /repos/{owner}/{repo}/issues` 응답 중 `pull_request` 키가 있는 항목(PR)은 제외한다.

| 정규화 키 | GitHub 원본 필드 |
|-----------|-----------------|
| `external_id` | `id` (불변 정수 id, 문자열화). 없으면 그 이슈는 발행하지 않는다 |
| `issue_key` | `"#" + number` — 사람이 실제로 쓰는 표기. 프로젝트당 저장소가 하나라 프로젝트 안에서 유일하다 |
| `title` · `body` | 각각 그대로 (`body` null → `""`) |
| `status_category` | `state` — `closed` → `closed`, 그 외 `open`. GitHub엔 in_progress 신호가 없다 |
| `status` | 닫혔으면 `state_reason`(`completed` \| `not_planned`, null이면 `closed`), 열려 있으면 `open` — "안 하기로 한 이슈"를 구분하기 위해 사유를 원문으로 싣는다 |
| `closed_at` | `status_category == closed`일 때만 `closed_at` |
| `issue_type` · `priority` | 생략 — GitHub `labels`는 자유 문자열 복수라 1:1 대응이 없다(Asana·ClickUp과 같은 처리) |
| `actor` | `user` → `{id: login, name, email}`. name/email은 수집 시 `GET /users/{login}`으로 보강 |
| `refs.assignees` | `assignees` 배열 → `[{id: login, name, email}]`. 없으면 빈 배열(키는 유지). login 없는 항목 제외. 프로필 보강은 작성자와 같은 캐시를 탄다(이슈 페이지에서만 — PR의 assignees는 쓰지 않아 보강하지 않는다) |
| `refs.issueKey`… | `title + "\n\n" + body`에서 기존 `RefsExtractor`로 추출. **`#N` 텍스트 참조 패턴은 없다** — 이슈·PR이 번호를 공유해 오연결 위험이 있어 별도 작업으로 미뤘다 |

`occurredAt`: `updated_at`(없으면 `created_at`).

ai-engine은 GitHub 봇 계정(`[bot]` 접미 login)이 만든 이슈를 커밋·PR과 같은 게이트로 버린다.
프론트 그래프 뷰에서는 다른 트래커의 이슈와 같은 `issue` 분류로 그려지고, 출처(GitHub·Jira·Linear…)는
노드의 `source`로 구분된다.

### Communication — 대화 (자연키: `url`)

Slack·Discord·Google Chat 등 대화 소스가 **공용**으로 쓴다. 그래서 소스별 삭제가 라벨이 아니라
`source` 속성으로 걸러진다.

| 키 | 타입 | 비고 |
|----|------|------|
| `url` | string | **자연키. 없으면 ai-engine이 이벤트를 버린다** |
| `body` | string | 임베딩 대상 |
| `channel` | string | 채널/공간 이름 |
| `conversation_id` | string | 스레드 묶음 키. 루트는 자기 자신, 답글은 부모의 키 |
| `created_at` | string \| null | |

`occurredAt`: 메시지 시각.

### Document — 문서 (자연키: `external_id` — source와 함께 유니크)

Notion이 첫 사례다(`docs/notion-integration.md`). 한 페이지가 수만 자일 수 있어, ai-engine은
`body`를 통짜로 임베딩하지 않고 heading 경계로 쪼갠 `DocumentSection`마다 임베딩한다 — 시맨틱
엣지는 `Document`에 건다.

| 키 | 타입 | 비고 |
|----|------|------|
| `external_id` | string | **자연키. 없으면 ai-engine이 이벤트를 버린다.** 플랫폼 **불변 ID**(Notion page id). 그래프 MERGE 키는 `(project_id, source, external_id)` |
| `title` | string | 페이지 제목 |
| `body` | string | 평문화된 본문. `heading_1/2/3`은 `#`/`##`/`###` 접두를 보존한다 — ai-engine 청킹의 경계 신호다 |
| `url` | string | 표시·링크용. **자연키가 아니다**(제목이 바뀌면 URL도 바뀐다) |
| `created_at` | string | 생성 시각 |
| `parent_type` | string | `page_id` \| `database_id` \| `data_source_id` \| `workspace` |
| `parent_external_id` | string \| 생략 | 부모 **page** id. 부모가 page가 아니면 생략 — `CHILD_OF` 매칭 키. **`properties`에 둔다** — Issue의 부모 키(`refs.parentExternalId`, 아래 「refs — 교차 참조」)와 위치가 다르니 혼동하지 말 것 |

`occurredAt`: 최종 수정 시각 — checkpoint 전진 기준. 편집된 문서는 이 값이 갱신돼 다시
수집 대상 상단으로 올라온다(대화 아키타입에는 없는, 문서 아키타입만의 재수집 신호).

---

## refs — 교차 참조

`refs`는 Layer 2(명시적 엣지)를 만드는 재료다. 텍스트에서 정규식으로 추출하거나
(`normalizer/RefsExtractor`) API 필드에서 직접 채운다.

| 키 | 타입 | 소비처 | 효과 |
|----|------|--------|------|
| `issueKey` | string | ChangeSet, Communication | 이슈로 `TRIGGERED_BY` / `DISCUSSED_IN`. 사람용 키로 실노드를 찾고, 없으면 `__stub__` 센티널 Issue에 걸어둔다 (stub 규약은 `docs/graph-schema.md`) |
| `issueKeys` | string[] | PullRequest, Document | PullRequest는 머지한 모든 커밋에 이슈 연결을 전파한다. Document는 이슈로 `DESCRIBED_IN`(`source='text'`)을 건다 — 문서 한 건이 여러 이슈를 명시하는 게 흔해 복수형만 쓴다 |
| `issueExternalRefs` | {source, externalId}[] | ChangeSet, PullRequest, Communication, Document | 이슈 키가 없는 소스(Asana 등)의 URL 참조. `(project_id, source, external_id)` **실키**로 직접 Issue pre-node를 MERGE하고(부모 참조와 동일 메커니즘, `__stub__` 센티널 불필요) 이슈로 `TRIGGERED_BY` / `DISCUSSED_IN` / `DESCRIBED_IN`(Document) text 엣지를 건다. PullRequest는 `"SOURCE:externalId"` 문자열 배열로 `issue_external_ids` 속성에 저장해 CONTAINS 커밋에 전파한다 |
| `documentExternalRefs` | {source, externalId}[] | ChangeSet, PullRequest, Communication | **역방향** 문서 참조 — 커밋/PR/대화 텍스트에 박힌 Notion URL(§normalizer가 문서 URL 패턴에서 추출). `issueExternalRefs`와 동일한 실키 pre-node 메커니즘으로 Document를 향해 text `REFERENCE`(ChangeSet)/`DISCUSSED_IN`(Communication)를 건다. PullRequest는 `issue_external_ids`와 같은 인코딩으로 `document_external_ids` 속성에 저장해 CONTAINS 커밋에 text `REFERENCE`로 전파한다 — 실무에서 Notion 링크는 커밋 메시지보다 PR 본문에 흔하다 |
| `prNumber` | string | ChangeSet | PR → 커밋 `CONTAINS` |
| `parentExternalId` | string | Issue | 부모의 **불변 ID** — `CHILD_OF` 매칭 키. 이 값이 있어야 링크된다. **Document는 이 refs 키를 쓰지 않는다** — `properties.parent_external_id`다(위 Document 표 참고). 같은 이름의 키가 nodeType마다 다른 곳에 있으니 새 문서 소스를 붙일 때 이 표만 보고 Document의 부모 키를 refs에 넣지 않도록 주의 |
| `parentIssueKey` | string | Issue | 부모 pre-node의 표시 키 (노드 생성 시에만 기록) |
| `assignees` | {id, name, email, bot}[] | Issue | 각 담당자를 Actor로 승격 후 `ASSIGNED_TO` (담당자 수만큼 엣지). `id`가 null인 항목은 발행 측에서 제외한다. `bot`은 선택(§actor) |
| `editors` | {id, name, email, bot}[] | Document | 최종 편집자(Notion `last_edited_by`) 1명을 배열로 감싼 것. 각 편집자를 Actor로 승격 후 `EDITED`. **누적 규약** — 아래 참고 |

**담당자 해제 규약**: Issue 이벤트는 최신 스냅샷이다. `assignees`가 없거나 빈 배열이면 "담당자
없음"으로 해석돼 기존 `ASSIGNED_TO`가 전부 제거되고, 배열에서 빠진 기존 담당자도 해제된다.
담당자 정보를 못 가져온 경우와 구분되지 않으므로, **조회 실패 시에는 Issue 이벤트 자체를
발행하지 않는** 편이 안전하다.

**편집자 누적 규약**: `assignees`와 의도적으로 **반대**다. Notion은 언제나 *마지막* 편집자만
알려주므로, 담당자와 같은 스냅샷 교체 규약을 적용하면 편집자가 바뀔 때마다 이전 편집자의
`EDITED`가 지워져 "이 문서에 누가 손댔나"가 항상 1명으로 보인다. 그래서 `editors`는 **MERGE만
하고 지우지 않는다** — 배열에 없다고 해제되지 않는다. 같은 모양의 배열이 반대로 동작하므로
다음 커넥터를 붙일 때 이 문단을 놓치면 반드시 틀린다.

---

## 발행·소비 규약

**발행 (pipeline-worker)**
- 이벤트 1건 = 메시지 1건. publisher confirm으로 브로커 수신을 검증한다.
- 한 건이라도 nack·unroutable·타임아웃이면 예외를 던져 **checkpoint를 전진시키지 않는다** —
  다음 수집에서 재발행된다. 커넥터는 이 규약을 깨면 안 된다(발행 예외를 삼키면 영구 누락).
- checkpoint 전진 기준은 그 배치의 **최대 `occurredAt`**이며, cursor는 과거로 되돌아가지 않는다.

**소비 (ai-engine)**
- 실패한 이벤트는 지연 재시도 큐 → 소진 시 DLQ로 보관한다(조용히 버리지 않는다).
- 같은 프로젝트의 이벤트는 직렬 처리(노드 경합·Actor race 방지), 프로젝트 간은 병렬이다.
- 모든 쓰기는 `(project_id, 자연키)` 복합키 MERGE라(Issue는 `(project_id, source, external_id)`)
  **재발행은 멱등**이다. 커넥터는 중복 발행을 두려워하지 말고 누락을 두려워해야 한다.

---

## 새 커넥터 체크리스트 — 수집 계약 부분

> ⚠️ **이 목록은 커넥터 작업의 전부가 아니다.** 여기서 다루는 건 pipeline-worker의 발행 계약뿐이라,
> 이것만 따라가면 **연동 UI 없이 수집기만 만들고 끝난다.** backend 연결(OAuth·선택 단계)과
> web-dashboard 등록까지 포함한 전체 순서는
> **`docs/integration-abstraction.md`의 「커넥터 엔드투엔드 체크리스트」**를 따른다.
> 아래 항목은 그 체크리스트의 2단계(pipeline-worker)에 해당한다.

1. **아키타입 선택** — 이슈 트래커(`Issue`) / 대화(`Communication`) / 문서(`Document`).
   기존 아키타입이면 ai-engine 무변경이다. `Document`는 Notion이 첫 사례이자 유일한 예외로,
   ai-engine 그래프 소비 경로(청킹·벡터 인덱스·Layer 2 text 엣지)가 이미 갖춰져 있다
   (`docs/notion-integration.md`) — 다음 문서 소스는 정말로 ai-engine 무변경일 수 있다.
2. `CollectionProvider`에 provider 추가, routing key 설정 추가 (§표기 규칙).
3. `source/{provider}` 패키지에 `SourceCollector` 구현 — fetch·normalize·publish·checkpoint.
4. 자연키가 **프로젝트 안에서 고유하고 불변**인지 확인. Issue는 `(project_id, source,
   external_id)` 키라 소스 간 충돌이 없지만, Communication(`url`)처럼 source가 키에 없는
   아키타입은 프로젝트 안 충돌이 데이터 병합 사고다. 사람용 키(`HT-7`)처럼 **바뀔 수 있는
   값은 자연키로 쓰지 않는다** — 표시용 속성(`issue_key`)으로 따로 싣는다.
5. `actor.id`가 안정적·고유한지 확인 (§actor).
6. `occurredAt`이 실제 발생 시각인지 확인 — checkpoint 정확도가 여기 달렸다.
7. `refs` 추출 패턴 기여 (이슈 키 형식 / URL 형식). URL 형식 소스는 `issueExternalRefs`로 수렴한다 — 첫 사례는 Asana.
8. 개인정보(이름·이메일) 취급이 `docs/graph-schema.md`의 ActorAlias 규약을 따르는지 확인.
9. 연동 해제 시 소스별 그래프 삭제가 동작하는지 확인 —
   `DELETE /graph/projects/{id}/sources/{SOURCE}`는 `source` 속성 기반이라 자동으로 맞는다.

## 키 중립화 이력 (A6 완료)

Jira 유래 명칭은 `docs/integration-abstraction.md` A6에서 소스 중립 이름으로 바꿨다.
**새 커넥터는 위 표의 현행 키(`issue_key`·`issueKey(s)`·`parentIssueKey`)만 쓴다.**

| 옛 이름 (더 이상 발행 금지) | 현행 |
|------|------|
| `properties.jira_key` | `issue_key` (표시용으로 강등 — 자연키는 `external_id`) |
| `refs.jiraKey` / `jiraKeys` | `issueKey` / `issueKeys` |
| `refs.parentJiraKey` | `parentIssueKey` (+ 매칭 키는 `parentExternalId`) |
| `refs.assigneeId` / `assigneeName` / `assigneeEmail` | `assignees: [{id, name, email}]` |

**저장된 그래프에 이행 장치는 없다.** 개발 단계라 보존할 데이터가 없어 기동 시 마이그레이션을
두지 않기로 했다 — 옛 키로 저장된 노드가 남아 있는 환경은 **그래프를 새로 구축한다**
(`DELETE /graph/projects/{id}` 후 재수집). 이행 장치를 되살리는 편이 나은 시점이 오면
배치 처리·옛/새 키 중복 노드 검증·테스트가 함께 필요하다.

인플라이트 이벤트 호환 레이어(`_normalize_legacy_keys`)는 Issue 키 일반화
(`(project_id, source, external_id)` 전환) 때 **제거됐다** — 옛 형식 이벤트는 `external_id`가
없어 소비 진입점에서 자연 폐기된다.
