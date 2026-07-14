# Query Tools — LLM tool-calling 도구 레퍼런스

ai-engine가 Neo4j 지식 그래프를 조회하기 위해 LLM에 제공하는 tool-calling 도구.
LLM은 자연어 질문에서 파라미터를 추출해 도구를 호출하고, 그 결과(JSON)를 근거로 답한다.

이 문서는 **현재 코드의 도구 계약**을 정리한 참고용 문서다. 도구를 수정·추가할 때 아래 세 파일을
함께 본다 — 불일치가 있으면 **코드가 진실**이다.

| 파일 | 역할 |
|------|------|
| `services/ai-engine/tools/definitions.py` | LLM에 노출되는 tool 스키마(이름·설명·파라미터). OpenAI function-calling 포맷 |
| `services/ai-engine/tools/queries.py` | 각 도구의 실제 Neo4j Cypher 구현. 반환 구조의 진실 |
| `services/ai-engine/tools/executor.py` | tool_name → queries 함수 디스패치. project_id 주입, 이메일 마스킹, 결과 크기 제한 |

> 도구를 추가하려면 세 곳을 모두 손봐야 한다: `definitions.TOOLS`에 스키마, `queries.py`에 함수,
> `executor._dispatch`에 case. 이름이 세 곳에서 정확히 일치해야 한다.

---

## 모든 도구에 공통으로 적용되는 규칙

executor / queries 레벨에서 일괄 적용되므로 도구별 설명에서는 반복하지 않는다.

- **project_id 자동 스코프**: `execute(tool_name, args, project_id)`가 backend로부터 인증된
  사용자의 `project_id`를 주입한다. 모든 Cypher가 이 값으로 스코프되어 다른 프로젝트 그래프를
  조회할 수 없다. **LLM은 project_id를 보지도 못하고 인자로 바꿀 수도 없다** (도구 파라미터에 없음).
- **TRIGGERED_BY confidence 컷오프** (`_MIN_CONFIDENCE = 0.5`): TRIGGERED_BY 엣지를 따라가는
  모든 도구는 `confidence >= 0.5`만 통과시킨다. 텍스트 매칭(`source='text'`)은 항상 `1.0`이라
  항상 통과하고, 시맨틱 매칭은 0.5 미만이면 응답에서 제외된다.
- **link_source 노출**: TRIGGERED_BY를 반환하는 도구는 각 항목에 `link_source`(`'text'` |
  `'semantic'`)와 `confidence`를 함께 실어, LLM이 확정 연결과 추정 연결을 구분하게 한다.
- **Slack 스레드 그룹핑**: Communication을 반환하는 도구(`get_issue_context`,
  `get_changeset_context`, `get_conflict_context`, `get_pr_context`)는 flat 메시지 목록을
  `conversation_id` 기준으로 묶어 `{conversation_id, source, channel, messages[...]}` 구조로
  반환한다. LLM이 서로 다른 스레드를 한 대화로 합치거나 화자를 뒤섞지 않게 하기 위함
  (`queries._group_communications_by_thread`).
- **결과 크기 제한**: 도구 결과 JSON이 8000자(`_MAX_RESULT_CHARS`, 약 4k 토큰)를 넘으면 잘리고
  "limit을 줄이거나 더 좁은 범위로 재호출하라"는 안내가 붙는다.
- **에러 / 빈 결과 형태**: 필수 인자 누락은 `{"error": "필수 인자 누락: ..."}`, 내부 예외는
  `{"error": "... 내부 오류가 발생했습니다."}`(상세는 로그에만). 조회 결과가 없으면 각 도구가
  `{"message": "..."}` 또는 `[{"message": "..."}]`를 반환한다.
- **이메일 마스킹**: 로그에 남기는 인자는 이메일이 마스킹된다(응답 데이터 자체는 마스킹 안 함).

노드/엣지의 속성 정의는 중복을 피해 [`graph-schema.md`](graph-schema.md)를 따른다.

---

## 도구 목록

| # | 도구 | 역할 | queries.py |
|---|------|------|-----------|
| 1 | `get_issue_context` | Jira 이슈 + 자식 이슈까지 관련 작업/논의 집계 | `get_issue_context` |
| 2 | `get_changeset_context` | 커밋 hash로 변경 이유(이슈/논의/PR/diff) 조회 | `get_changeset_context` |
| 3 | `find_expert` | 파일/디렉토리 최다 기여자 식별 (최근 6개월 2배 가중) | `find_expert` |
| 4 | `get_timeline` | 이슈 생명주기 이벤트를 시간순 + 의미 라벨로 반환 | `get_timeline` |
| 5 | `search_by_keyword` | 자연어 키워드 시맨틱 검색 (Communication + Issue) | `search_by_keyword` |
| 6 | `get_actor_activity` | 사람(이름/alias/email) 중심 활동 조회 | `get_actor_activity` |
| 7 | `get_file_history` | 파일 변경 이력 + 경로 fuzzy fallback | `get_file_history` |
| 8 | `check_missing_context` | 이슈·논의 어디에도 연결 안 된 고아 커밋 탐지 | `check_missing_context` |
| 9 | `inspect_actor` | Actor 통합 결과·confidence·활동 집계 확인 | `inspect_actor` |
| 10 | `get_conflict_context` | 한 커밋의 출처별(Jira/Slack/PR) 맥락 분리 반환 | `get_conflict_context` |
| 11 | `get_recent_activity` | 기간 내 전 노드 타입 활동을 최신순 혼합 반환 | `get_recent_activity` |
| 12 | `get_pr_context` | PR 번호로 커밋/이슈/논의/파일 변경 조회 | `get_pr_context` |
| 13 | `get_thread_context` | Slack 스레드를 conversation_id로 전체 조회 | `get_thread_context` |

---

## 도구 상세

각 항목은 **LLM 계약(definitions.py)** 기준 파라미터 + 반환 핵심 + 비자명 동작 순.
전체 Cypher와 정확한 반환 구조는 queries.py의 동일 이름 함수를 참조한다.

### 1. `get_issue_context`

Jira 이슈를 기준으로 관련 커밋·PR·논의를 조회한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `jira_key` | string | ✔ | Jira 티켓 키 (예: `HT-12`) |

- 반환: 이슈 메타(`title`/`body`/`status`/`creator`/`assignee` 등) + root 이슈에 직접 연결된
  `changesets` / `pull_requests` / `discussions`, 그리고 **`descendants[]`**.
- **CHILD_OF 자식 이슈까지 집계**: epic/스토리 구조를 따라 `CHILD_OF*1..5`로 자식 이슈를 모아
  각각의 작업/논의를 `descendants`에 담는다 (root 작업은 하위 호환을 위해 top-level에도 그대로 둠).
- `changesets[*]`에 `confidence` + `link_source` 포함. `discussions`는 스레드 그룹핑 구조.

### 2. `get_changeset_context`

커밋 hash로 "왜 이 코드가 바뀌었는지"를 조회한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `hash` | string | ✔ | Git commit hash |

- 반환: `hash`, `commit_message`, `author`, `issues[]`(연결 이슈, confidence/link_source),
  `communications`(스레드 그룹핑), `pull_request`(단일), `file_changes[]`(`path`+`diffSummary`).
- 논의는 `REFERENCE` 엣지 기준(커밋→Communication).

### 3. `find_expert`

특정 파일/디렉토리 최다 기여자를 식별한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `path_prefix` | string | ✔ | 파일 경로 또는 디렉토리 접두어 (`src/auth/`, `src/auth/token.py`) |

- `f.path STARTS WITH path_prefix`로 매칭. **최근 6개월(P180D) 커밋에 가중치 2배**를 적용한
  `weighted_score` 내림차순, 상위 5명 반환.
- 반환 항목: `author`, `actor_uuid`, `commit_count`, `weighted_score`, `last_commit`.

### 4. `get_timeline`

이슈 생명주기를 시간순으로 반환한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `jira_key` | string | ✔ | Jira 티켓 키 |

- 각 이벤트에 **`event_meaning` 라벨**을 붙여 LLM이 occurredAt만 보고 추정하지 않게 한다:
  `issue_created` / `issue_closed`(closedAt 존재 시) / `commit_authored` / `pr_opened`(createdAt) /
  `pr_merged`(occurredAt) / `message_posted`.
- 항목 구조 `{type, event_meaning, occurredAt, data}`, occurredAt 오름차순. null occurredAt은 제외.

### 5. `search_by_keyword`

자연어 키워드를 임베딩해 의미적으로 유사한 Communication과 Issue를 찾는다.

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
|---------|------|------|------|------|
| `keyword` | string | ✔ | — | 검색할 자연어 키워드/문장 |
| `top_k` | integer | | 5 | 각 인덱스에서 반환할 최대 후보 수 |
| `threshold` | number | | 0.30 | 최소 코사인 유사도 |

- **LLM은 `keyword`(문자열)만 전달한다.** executor가 `embed_text(keyword)`로 임베딩을 만들어
  queries에 넘긴다(queries 함수 시그니처는 `embedding: list[float]`).
- Neo4j 벡터 인덱스 `comm_embedding` / `issue_embedding`을 사용. `db.index.vector.queryNodes`는
  전역 top-K만 주고 project_id 사전 필터가 불가하므로, **`top_k`의 20배(상한 500)만큼 over-fetch한 뒤
  project_id로 후필터하고 top_k로 자른다**.
- 같은 스레드 중복 메시지는 최고 score 1건만 남긴다(이후 `get_thread_context`로 전체 확보 유도).
- 각 항목에 `related_changesets`(hash) / `related_issues`(jira_key)를 실어 다음 도구 호출의 진입점 제공.

### 6. `get_actor_activity`

한 사람의 커밋·PR·메시지·이슈 활동을 조회한다.

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
|---------|------|------|------|------|
| `identifier` | string | ✔ | — | 이름, alias(GitHub login 등), 또는 이메일 중 하나 |
| `from_time` | string | | (전체) | 조회 시작 시각 ISO-8601. 생략 시 전체 |
| `limit` | integer | | 20 | **항목 종류별** 최대 반환 수 |

- **alias/email 통합 매칭**: `a.name = identifier OR identifier IN a.aliases OR identifier IN a.emails`.
  Identity Resolution으로 통합된 Actor를 단일 식별자로 찾는다.
- 반환: `name`/`aliases`/`emails` + `changesets`/`pull_requests`/`communications`(각 최신순 limit개) +
  `issues_created`/`issues_assigned`.
- **종료 시각(`to`/`to_time`) 파라미터는 없다** — `from_time` 이후 전체.

### 7. `get_file_history`

파일의 변경 이력을 최신순으로 반환한다.

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
|---------|------|------|------|------|
| `path` | string | ✔ | — | 파일 경로 (예: `src/auth/token.py`) |
| `limit` | integer | | 20 | 최대 반환 커밋 수 |

- 정상 항목: 커밋당 1행 — `hash`, `message`, `author`, `diff_summary`(행당 300자 컷),
  `issues[]`(각 `jira_key`/`title`/`confidence`/`source`), `prs[]`(각 `pr_number`/`url`).
  이슈·PR 링크가 여러 개여도 행이 곱으로 불어나지 않는다.
- **경로 fuzzy fallback**: strict 매칭이 0건이면 ① basename(`.../token.py`) ENDS WITH →
  ② stem(확장자 무관, `token`) 순으로 후보를 찾는다.
  - 후보 **정확히 1개** → 그 파일 이력을 반환하되 각 row에 `_resolved_via`(`basename_match` |
    `stem_match`) / `_resolved_path` 메타를 인라인 부여. **evidence에는 LLM이 추정한 path가 아니라
    `_resolved_path`를 써야 한다.**
  - 후보 **2개 이상** → `[{message, candidates: [...]}]` 단건 반환. LLM이 candidates 중 정확한
    경로로 재호출.

### 8. `check_missing_context`

이슈(TRIGGERED_BY)와도 논의(REFERENCE)와도 연결되지 않은 고아 커밋을 탐지한다.

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
|---------|------|------|------|------|
| `from_time` | string | | (전체) | 조회 시작 시각 ISO-8601 |
| `to_time` | string | | (현재) | 조회 종료 시각 ISO-8601 |
| `limit` | integer | | 50 | 최대 반환 수 |

- confidence 컷오프 적용: `confidence >= 0.5`인 TRIGGERED_BY만 "연결됨"으로 본다(약한 시맨틱
  링크만 있는 커밋도 고아로 잡힐 수 있음). 반환: `hash`/`message`/`author`/`occurredAt`/`files`.

### 9. `inspect_actor`

Actor 통합 결과를 확인한다 (Identity Resolution 검증).

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `identifier` | string | ✔ | 이름, alias, 또는 이메일 중 하나 |

- 반환: `uuid`, `display_name`, `normalized_name`, `all_aliases`, `emails`,
  `merge_confidence`, 활동 집계(`commit_count`/`pr_count`/`message_count`/`issue_created_count`).

### 10. `get_conflict_context`

한 커밋에 대해 Jira/Slack·GitHub/PR이 서로 다른 맥락을 줄 때 출처별로 분리해 반환한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `hash` | string | ✔ | Git commit hash |

- 반환: `jira_contexts[]`(confidence/link_source), `comm_contexts`(스레드 그룹핑),
  `pr_contexts[]`, `file_changes[]`. LLM이 다중 관점을 비교해 실제 이유를 추론하도록 설계.

### 11. `get_recent_activity`

"최근에 뭐 바뀌었어?"처럼 범위가 모호한 질문에 쓴다.

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
|---------|------|------|------|------|
| `from_time` | string | ✔ | — | 시작 시각 ISO-8601. LLM이 "최근 7일" 등을 계산해 전달 |
| `to_time` | string | | (현재) | 종료 시각 ISO-8601 |
| `limit` | integer | | 30 | 최대 반환 수 |

- ChangeSet/PullRequest/Communication/Issue를 한꺼번에 occurredAt 최신순으로 반환.
  각 항목 `{type, occurredAt, actor, id, summary}` (id/summary는 타입별로 hash·pr_number·url·jira_key
  와 message·title·body로 매핑). **`from_time`은 필수다.**

### 12. `get_pr_context`

PR 번호로 시작하는 탐색.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `pr_number` | integer | ✔ | GitHub PR 번호 |

- 반환: PR 메타(`title`/`body`/`merged_at`/`created_at`/`url`/`author`) + `changesets[]` +
  `issues[]`(confidence/link_source) + `discussions`(스레드 그룹핑) + `file_changes[]`.

### 13. `get_thread_context`

Slack 스레드를 conversation_id로 완전히 조회한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `conversation_id` | string | ✔ | Slack 스레드 루트 메시지 ts (예: `1773799131.000200`) |

- **Slack 전용** — GitHub Issue 등 다른 소스에는 사용하지 않는다.
- 반환: 스레드 내 메시지를 occurredAt 오름차순으로, 각 메시지에 `author`와 연결 `related_issues` 포함.
- 정렬은 RETURN alias `occurredAt`(ISO 문자열) 기준 — collect aggregation 때문에 `c.occurredAt`을
  ORDER BY에서 직접 못 쓴다(코드 주석 참고).

---

## Tool Calling 조합 예시

도구를 어떻게 엮어 쓰는지 보여주는 대표 흐름.

**"결제 모듈 리팩토링 왜 했어?"** — 진입점을 모를 때 시맨틱 검색으로 시작
```
1. search_by_keyword(keyword="결제 리팩토링")
   → related_changesets: ["abc123"], related_issues: ["HT-8"]
2. get_changeset_context(hash="abc123")
   → issues: [{jira_key:"HT-8", confidence:0.85, link_source:"semantic"}]
   → communications: [{conversation_id, messages:[...]}], file_changes: [...]
3. (선택) get_thread_context(conversation_id=...) → 스레드 전체 맥락
```

**"john-dev랑 jkim@co.com 같은 사람이야?"** — Identity Resolution 확인
```
inspect_actor("john-dev")
   → all_aliases: ["GITHUB:john-dev", "JIRA:account_abc"], emails: ["jkim@co.com"],
     merge_confidence: 0.91
```
