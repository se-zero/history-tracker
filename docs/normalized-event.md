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
  "nodeType": "ChangeSet | PullRequest | Issue | Communication",
  "source":   "GITHUB | JIRA | SLACK | ...",
  "occurredAt": "ISO-8601 Instant",
  "actor":  { "id": "", "name": "", "email": null },
  "properties": { },
  "refs": { }
}
```

정의는 `pipeline-worker`의 `dto/NormalizedEvent.java` · `dto/ActorDto.java`.

| 필드 | 필수 | 규칙 |
|------|------|------|
| `projectId` | ✅ | 프로젝트 UUID. **없으면 ai-engine이 이벤트를 버린다** — 프로젝트 스코프 없는 노드는 어떤 조회에도 속하지 못하고 자연키 충돌로 다른 프로젝트와 병합될 수 있다. |
| `nodeType` | ✅ | 아래 4종 중 하나. 알 수 없는 값은 경고 로그 후 폐기된다. |
| `source` | ✅ | **대문자** 소스 식별자. Actor alias 접두(`GITHUB:login`)와 소스별 그래프 삭제의 스코프 키를 겸한다. |
| `occurredAt` | ✅ | **이벤트의 실제 발생 시각**(수집 시각이 아니다). checkpoint 전진 기준이라 여기가 틀리면 데이터가 영구 누락된다. |
| `actor` | ✅ | 작성자. 소스별 ID 형식이 다르며 동일인 통합은 ai-engine이 한다. |
| `properties` | ✅ | `nodeType`별 스키마. 아래 참조. |
| `refs` | ✅ | 다른 시스템 참조. 없으면 빈 맵(`null` 금지). |

### actor

```java
record ActorDto(String id, String name, String email)
```

- `id` — 소스별 사용자 ID (GitHub `login`, Jira `accountId`, Slack `userId`).
  `SOURCE:id`가 `ActorAlias`의 자연키가 되므로 **소스 안에서 안정적·고유**해야 한다.
  사람이 바꿀 수 있는 표시 이름을 id로 쓰면 안 된다.
- `name` — 표시 이름. 개인정보라 `ActorAlias.pd_name`에만 저장된다.
- `email` — 없으면 `null`. 동일인 판단의 가장 강한 신호라 수집 가능하면 채운다.
  단 **협업 툴 계정의 이메일만** 쓴다 (예: git config 이메일은 개인정보라 사용 금지).

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

### Issue — 이슈/티켓 (자연키: `issue_key`)

| 키 | 타입 | 비고 |
|----|------|------|
| `issue_key` | string | **자연키**. 외부 트래커의 이슈 키 (Jira `HT-7`, Linear `ENG-42` 등) |
| `title` · `body` | string | 합쳐서 임베딩된다 |
| `status` | string | |
| `issue_type` · `priority` | string | |
| `created_at` | string | |
| `closed_at` | string | **종료 상태일 때만 넣는다.** 아래 3-상태 규약 참고 |

`occurredAt`: 최종 수정 시각(없으면 생성 시각).

**`closed_at` 3-상태 규약** — ai-engine이 `status`와 조합해 해석한다:

| `closed_at` | `status` | 결과 |
|-------------|----------|------|
| 있음 | terminal | `closedAt` 기록 |
| 없음 | non-terminal | `closedAt`을 **null로 클리어** (재오픈 처리) |
| 없음 | terminal | 기존 `closedAt` **보존** (구버전 호환) |

terminal 상태 집합은 커넥터와 ai-engine 양쪽이 같은 값을 유지해야 한다.

### Communication — 대화 (자연키: `url`)

Slack 메시지와 GitHub 이슈가 **공용**으로 쓴다. 그래서 소스별 삭제가 라벨이 아니라
`source` 속성으로 걸러진다.

| 키 | 타입 | 비고 |
|----|------|------|
| `url` | string | **자연키. 없으면 ai-engine이 이벤트를 버린다** |
| `body` | string | 임베딩 대상 |
| `channel` | string | 채널/공간 이름 (GitHub 이슈는 `github_issues` 고정) |
| `conversation_id` | string | 스레드 묶음 키. 루트는 자기 자신, 답글은 부모의 키 |
| `created_at` | string \| null | |

`occurredAt`: 메시지 시각 / 이슈 최종 수정 시각.

---

## refs — 교차 참조

`refs`는 Layer 2(명시적 엣지)를 만드는 재료다. 텍스트에서 정규식으로 추출하거나
(`normalizer/RefsExtractor`) API 필드에서 직접 채운다.

| 키 | 타입 | 소비처 | 효과 |
|----|------|--------|------|
| `issueKey` | string | ChangeSet, Communication | 이슈로 `TRIGGERED_BY` / `DISCUSSED_IN` |
| `issueKeys` | string[] | PullRequest | PR이 머지한 모든 커밋에 이슈 연결 전파 |
| `prNumber` | string | ChangeSet | PR → 커밋 `CONTAINS` |
| `parentIssueKey` | string | Issue | 부모 이슈 `CHILD_OF` |
| `assigneeId` / `assigneeName` / `assigneeEmail` | string | Issue | 담당자를 Actor로 승격 후 `ASSIGNED_TO` |

**담당자 해제 규약**: Issue 이벤트는 최신 스냅샷이다. `assigneeId`가 없으면 "담당자 없음"으로
해석돼 기존 `ASSIGNED_TO`가 제거된다. 담당자 정보를 못 가져온 경우와 구분되지 않으므로,
**조회 실패 시에는 Issue 이벤트 자체를 발행하지 않는** 편이 안전하다.

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
- 모든 쓰기는 `(project_id, 자연키)` 복합키 MERGE라 **재발행은 멱등**이다.
  커넥터는 중복 발행을 두려워하지 말고 누락을 두려워해야 한다.

---

## 새 커넥터 체크리스트 — 수집 계약 부분

> ⚠️ **이 목록은 커넥터 작업의 전부가 아니다.** 여기서 다루는 건 pipeline-worker의 발행 계약뿐이라,
> 이것만 따라가면 **연동 UI 없이 수집기만 만들고 끝난다.** backend 연결(OAuth·선택 단계)과
> web-dashboard 등록까지 포함한 전체 순서는
> **`docs/integration-abstraction.md`의 「커넥터 엔드투엔드 체크리스트」**를 따른다.
> 아래 항목은 그 체크리스트의 2단계(pipeline-worker)에 해당한다.

1. **아키타입 선택** — 이슈 트래커(`Issue`) / 대화(`Communication`) / 문서(`Document`, 미구현).
   기존 아키타입이면 ai-engine 무변경이다.
2. `CollectionProvider`에 provider 추가, routing key 설정 추가 (§표기 규칙).
3. `source/{provider}` 패키지에 `SourceCollector` 구현 — fetch·normalize·publish·checkpoint.
4. 자연키가 **프로젝트 안에서 고유**한지 확인. 자연키는 소스가 아니라 `(project_id, 자연키)`로
   격리되므로, 프로젝트 간 충돌은 문제없지만 프로젝트 안 충돌은 데이터 병합 사고다.
5. `actor.id`가 안정적·고유한지 확인 (§actor).
6. `occurredAt`이 실제 발생 시각인지 확인 — checkpoint 정확도가 여기 달렸다.
7. `refs` 추출 패턴 기여 (이슈 키 형식 / URL 형식).
8. 개인정보(이름·이메일) 취급이 `docs/graph-schema.md`의 ActorAlias 규약을 따르는지 확인.
9. 연동 해제 시 소스별 그래프 삭제가 동작하는지 확인 —
   `DELETE /graph/projects/{id}/sources/{SOURCE}`는 `source` 속성 기반이라 자동으로 맞는다.

## 키 중립화 이력 (A6 완료)

Jira 유래 명칭은 `docs/integration-abstraction.md` A6에서 소스 중립 이름으로 바꿨다.
**새 커넥터는 위 표의 현행 키(`issue_key`·`issueKey(s)`·`parentIssueKey`)만 쓴다.**

| 옛 이름 (더 이상 발행 금지) | 현행 |
|------|------|
| `properties.jira_key` | `issue_key` |
| `refs.jiraKey` / `jiraKeys` | `issueKey` / `issueKeys` |
| `refs.parentJiraKey` | `parentIssueKey` |

호환 장치 (옛 키가 브로커·retry 큐·DLQ에서 더는 관측되지 않으면 제거해도 된다):
- ai-engine `graph/event_handler.py`의 `_normalize_legacy_keys`가 옛 키 이벤트를 진입점에서
  새 이름으로 정규화한다.
- 저장 데이터는 ai-engine 기동 시 `migrate_issue_key_rename`이 이행한다
  (`Issue.jira_key → issue_key`, `PullRequest.jira_keys → issue_keys`, idempotent).
