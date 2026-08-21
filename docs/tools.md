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
  `run_graph_query`만 주입 방식이 다르다 — LLM이 쓴 Cypher의 노드 패턴을 서버가 재작성해 넣는다(#14).
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
| 1 | `get_issue_context` | Jira 이슈 + 자식 이슈까지 관련 작업/논의/문서 집계 | `get_issue_context` |
| 2 | `get_changeset_context` | 커밋 hash로 변경 이유(이슈/논의/PR/diff) 조회 | `get_changeset_context` |
| 3 | `find_expert` | 파일/디렉토리 최다 기여자 식별 (최근 6개월 2배 가중) | `find_expert` |
| 4 | `get_timeline` | 시간순 이벤트를 스코프별(이슈/파일/사람/전체 ±기간)로 반환 | `get_timeline` |
| 4b | `rank_issues` | 이슈 전체를 논의량·진행기간으로 정렬해 상위 반환 | `rank_issues` |
| 5 | `search_by_keyword` | 자연어 키워드 시맨틱 검색 (Communication + Issue) | `search_by_keyword` |
| 6 | `get_actor_activity` | 사람(이름/alias/email) 중심 활동 조회 | `get_actor_activity` |
| 7 | `get_file_history` | 파일 변경 이력 + 경로 fuzzy fallback | `get_file_history` |
| 8 | `check_missing_context` | 이슈·논의 어디에도 연결 안 된 고아 커밋 탐지 | `check_missing_context` |
| 9 | `inspect_actor` | Actor 통합 결과·confidence·활동 집계 확인 | `inspect_actor` |
| 10 | `get_conflict_context` | 한 커밋의 출처별(Jira/Slack/PR) 맥락 분리 반환 | `get_conflict_context` |
| 11 | `get_recent_activity` | 기간 내 전 노드 타입 활동을 최신순 혼합 반환 | `get_recent_activity` |
| 12 | `get_pr_context` | PR 번호로 커밋/이슈/논의/파일 변경 조회 | `get_pr_context` |
| 13 | `get_thread_context` | Slack 스레드를 conversation_id로 전체 조회 | `get_thread_context` |
| 14 | `run_graph_query` | 전용 도구가 없는 질문에 Cypher 직접 실행 (범용 탈출구) | `explore.run_graph_query` |
| 15 | `describe_graph` | 라벨의 노드 수·속성·실제 값 분포 조회 | `explore.describe_graph` |
| 16 | `get_document_context` | 문서(Notion) external_id로 본문·작성자·편집자·연결된 이슈/커밋/대화 조회 | `document.get_document_context` |
| 17 | `search_documents` | 자연어 질의로 DocumentSection 시맨틱 검색 (문서+최고점 섹션 발췌) | `document.search_documents` |

---

## 도구 상세

각 항목은 **LLM 계약(definitions.py)** 기준 파라미터 + 반환 핵심 + 비자명 동작 순.
전체 Cypher와 정확한 반환 구조는 queries.py의 동일 이름 함수를 참조한다.

### 1. `get_issue_context`

이슈를 기준으로 관련 커밋·PR·논의를 조회한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `issue_key` | string | ✔ | 이슈 트래커의 사람용 키 (예: `HT-12`) — 표시용 속성으로 매칭하며 `__stub__` 센티널은 제외 |

- 반환: 이슈 메타(`title`/`body`/`status`/`creator`/`assignee` 등) + root 이슈에 직접 연결된
  `changesets` / `pull_requests` / `discussions` / `documents`, 그리고 **`descendants[]`**.
- **CHILD_OF 자식 이슈까지 집계**: epic/스토리 구조를 따라 `CHILD_OF*1..5`로 자식 이슈를 모아
  각각의 작업/논의/문서를 `descendants`에 담는다 (root 작업은 하위 호환을 위해 top-level에도 그대로 둠).
- `changesets[*]`에 `confidence` + `link_source` 포함. `discussions`는 스레드 그룹핑 구조.
- `documents[*]`는 `DESCRIBED_IN`(Issue→Document) 유입 — `confidence`/`link_source`('text'|'semantic')/
  `section` 포함, `get_document_context`의 `issues` 필드와 반대 방향의 같은 관계다. 문서 id를
  몰라도 이슈에서 바로 연결된 문서(설계 배경 등)를 찾을 수 있다.

### 2. `get_changeset_context`

커밋 hash로 "왜 이 코드가 바뀌었는지"를 조회한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `hash` | string | ✔ | Git commit hash |

- 반환: `hash`, `commit_message`, `author`, `issues[]`(연결 이슈, confidence/link_source),
  `communications`(스레드 그룹핑), `documents[]`(연결 문서, `REFERENCE` 엣지 기준),
  `pull_request`(단일), `file_changes[]`(`path`+`diffSummary`).
- 논의는 `REFERENCE` 엣지 기준(커밋→Communication).
- `documents[*]`에 `external_id`(`title`/`url`/`source`/`confidence`와 함께) 포함 — 이 값으로
  `get_document_context`를 호출해 본문 전체를 조회한다.

### 3. `find_expert`

특정 파일/디렉토리 최다 기여자를 식별한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `path_prefix` | string | ✔ | 파일 경로 또는 디렉토리 접두어 (`src/auth/`, `src/auth/token.py`) |

- `f.path STARTS WITH path_prefix`로 매칭. **최근 6개월(P180D) 커밋에 가중치 2배**를 적용한
  `weighted_score` 내림차순, 상위 5명 반환.
- 반환 항목: `author`, `actor_uuid`, `commit_count`, `weighted_score`, `last_commit`.

### 4. `get_timeline`

시간순 이벤트를 **스코프별로** 반환한다. 설계 배경은 [`timeline-scope.md`](timeline-scope.md).

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `issue_key` | string | | 이슈 스코프(사람용 키) — 생명주기 + 연결 커밋·PR·논의 |
| `path` | string | | 파일 스코프 — 그 파일을 바꾼 커밋 + 담은 PR |
| `actor` | string | | 사람 스코프 — 이름·alias·이메일 |
| `from_time` / `to_time` | string | | ISO-8601 기간 한정. 어느 스코프와도 조합 가능 |

- **스코프 우선순위**: `issue_key` > `path` > `actor` > (전부 생략) 프로젝트 전체.
  넷 다 없으면 전 기간 타임라인이라 **특정 엔티티 없는 시간순 질문에도 쓸 수 있다.**
- **순서 뼈대이지 인용 원문이 아니다.** `events` 항목은 식별자·제목 수준 개요라 본문이 없다.
  근거로 인용하려면 식별자로 상세 도구를 호출해 본문을 얻는다 — commit→`get_changeset_context`,
  message→`get_thread_context`, pull_request→`get_pr_context`, issue→`get_issue_context`.
  `get_file_history`·`get_actor_activity`의 `context` 계층과 같은 규약이다.
  여러 종류가 섞여 있으면 한 종류만 파고들지 않는다 — "왜/배경/발단" 질문에서 `message`
  이벤트는 논의의 출발점을 담는다.
  > 측정 근거: 이 규칙이 없던 런에서 모델이 타임라인을 상세 도구의 **대체**로 써
  > (case-05 `get_issue_context` 7→3회, case-40 `get_changeset_context` 3→0회)
  > 인용할 원문이 사라지며 evidence recall이 떨어졌다. 규칙 추가 후에도 커밋만 상세
  > 조회하는 쏠림이 남아(case-44: message 12건·PR #34가 출력에 떴으나 3런 모두 미인용),
  > 종류를 고르게 커버하라는 규칙을 덧붙였다.
- **이슈 스코프는 `CHILD_OF` 자식 이슈까지 포함**한다(`get_issue_context`와 같은 범위).
  root만 보면 epic의 "어떤 하위 작업들로 진행됐나"에 답할 수 없고, 정보량이 더 적은
  타임라인이 `get_issue_context` 드릴다운을 밀어내는 회귀가 생긴다
  (case-05: 자식 이슈 4건 조회가 타임라인 1회로 대체되며 recall 0.778 → 0.111).
- **노드 ≠ 이벤트**: 한 노드가 이벤트를 여러 개 낳는다. 매핑 단일 출처는
  `tools/queries/_common.py`의 `EVENT_SPECS`이며, `agent/orchestrator.py`의 타임스탬프 의미
  사전과 함께 움직여야 한다. 단, `orchestrator.py` 쪽은 Document(`document_created`/
  `document_updated`)를 하나 더 갖는 상위집합이다 — Document는 작업 단위가 아니라 맥락이라
  `get_timeline`엔 들어가지 않지만, `get_issue_context.documents[]`·`get_document_context`로
  조회된 뒤엔 evidence로 인용될 수 있어야 하기 때문이다.

  | 노드 | 시각 속성 | `event_meaning` |
  |---|---|---|
  | Issue | `createdAt` / `closedAt` | `issue_created` / `issue_closed` |
  | PullRequest | `createdAt` / `occurredAt` | `pr_opened` / `pr_merged` |
  | ChangeSet | `occurredAt` | `commit_authored` |
  | Communication | `occurredAt` | `message_posted` |

  `Issue.occurredAt`(최종 업데이트)은 생성도 종료도 아니라 **이벤트로 만들지 않는다** —
  라벨 없이 노출하면 모델이 생성/완료로 추정해 뒤집는다(`query-quality-issues.md` 문제 2·3).
- **시각은 UTC로 정규화**된다(밀리초 고정). 그래프에는 `Issue.createdAt`만 `+09:00` 오프셋으로,
  나머지는 `Z`로 저장돼 있어 사전순 정렬이 시간순과 어긋나기 때문 (`_common._event_time`).
- **표시 변환은 서버가 하지 않는다.** ai-engine은 답변 본문(`summary`)과 `evidence[*].occurredAt`
  모두 UTC ISO 정준값을 그대로 내보내고, 사용자 화면의 현지 시간 변환은 web-dashboard의
  `lib/remarkLocalTime.ts`가 렌더 시점에 담당한다(뷰어 기기 타임존 + UI 로캘). 서버가 특정
  타임존으로 문자열을 굳히면 저장된 답변이 그 타임존에 영구히 묶이고, UTC 기준으로 적힌
  eval 골든셋 기대값도 함께 깨진다. 이 분업 때문에 시스템 프롬프트는 모델에게 본문에서도
  **ISO 원문을 그대로 옮기고 날짜를 말로 풀어 쓰지 말라**고 지시한다 — 풀어 쓰면 클라이언트가
  변환할 대상을 잃는다.
- **창 필터는 이벤트 단위**다. 노드 단위로 자르면 창 이전에 생성돼 창 안에서 종료된 이슈의
  종료 이벤트를 잃는다.
- 반환 구조:
  ```
  {scope: {type: issue|path|actor|project, value,
           created_at?, closed_at?, status?,     # issue 스코프: root 이슈 생애 (권위값)
           resolved_path?, resolved_via?, candidates?},
   window: {requested_from, requested_to, covered_from, covered_to},
   total_events, events: [{type, event_meaning, occurredAt, data}], truncated?}
  ```
  - `events`는 occurredAt 오름차순. 시각 없는 이벤트는 제외.
  - **"얼마 동안 진행됐어"류 기간 질문은 issue 스코프의 `scope.created_at`~`scope.closed_at`으로**
    답한다. 이슈 노드의 생애 속성이라 잘림과 무관한 권위값이다. events의 처음·마지막으로
    기간을 계산하면 자식 이슈·커밋 활동이 섞이거나 잘려 틀린다(HT-3가 41일로 오답 나던 원인).
  - **`covered_from`/`covered_to`는 실제 처음·마지막 사건 시각**이다. 아래 양끝 보존 잘림 덕에
    `truncated`가 있어도 전체 기간으로 그대로 쓸 수 있다(가운데 사건만 빠짐).
- **잘림은 양끝을 보존하고 가운데를 자른다.** "얼마 동안"류는 시작·끝이 둘 다 필요한데, 뒤만
  자르면 끝점(issue_closed 등)을 잃어 기간을 틀리게 계산한다. 예산 `TIMELINE_BUDGET_CHARS`
  (기본 7000, executor 상한 8000 아래)에 맞춰 오래된 앞 + 최신 뒤를 남기므로 executor의
  바이트 재컷이 뒤늦게 끼어드는 이중 잘림이 없다. 이벤트는 순서 뼈대라 커밋 메시지·제목은
  첫 줄만(`TIMELINE_TITLE_CHARS` 120), 슬랙 본문은 앞부분만(`TIMELINE_BODY_CHARS` 200) 싣는다.
- **파일 스코프는 이슈를 이벤트로 펼치지 않는다.** 연결 이슈는 커밋 `data.issues`에 키로 실린다 —
  펼치면 파일 하나에 연결된 이슈 수십 개의 생성/완료가 정작 그 파일을 바꾼 커밋을 덮는다.
  경로 fuzzy 폴백(basename → stem)은 `get_file_history`와 같은 규칙이고, 후보가 2개 이상이면
  `scope.candidates`를 돌려 재호출을 유도한다.

### 4b. `rank_issues`

이슈 **전체를 지표로 정렬해 상위**를 반환한다. "가장 오래/많이 논의된 티켓", "가장 오래 걸린
이슈"처럼 전수 비교로 1등을 뽑는 질문용.

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
|---------|------|------|------|------|
| `by` | string | ✔ | — | `discussion`(DISCUSSED_IN 수) 또는 `duration`(생성→종료 경과일, 종료 이슈만) |
| `top_k` | integer | | 5 | 상위 개수 (최대 20) |

- **`get_timeline`과 역할이 다르다**: `get_timeline`은 *스코프가 주어진* 시간축이라 전수 비교를
  못 한다. 랭킹 질문을 `get_timeline`으로 답하면 어쩌다 걸린 한 이슈를 최상위로 오답낸다
  (실측: "가장 길게 논의된 티켓"에 HT-48을 답했으나 실제 discussion 1위는 HT-102·HT-94,
  duration 1위는 HT-3).
- 각 행에 `discussion_count`·`duration_days`를 **함께** 실어 모델이 맥락을 본다.
- **답변 본문에는 필드명이 아니라 사용자 표기로 쓴다** — `discussion_count` → "관련 대화 메시지 수",
  `duration_days` → "진행 기간(일)". 표기·정의의 단일 출처는 `services/ai-engine/agent/glossary.py`이고,
  서버가 `_sanitize_internal_terms`로 새는 것을 치환한다(경위는 docs/query-quality-issues.md 케이스 13).
  **새 지표를 추가하면 용어집에 표기를 함께 등록한다** — 이름을 주지 않으면 모델이 필드명을 그대로 쓴다.
- `discussion_count`는 연결된 **메시지 수**다(스레드 수가 아니다). `DISCUSSED_IN`은 텍스트 참조·유사도
  추정에 더해 **같은 스레드로 전파**(`propagated`)되므로, 긴 스레드 하나가 수치를 지배할 수 있다
  (실측: HT-102의 13건 중 12건이 스레드 하나). "논의 횟수"로 서술하면 오해를 만든다.
- **경과일은 epoch 차이로 계산**한다 — `duration.between(...).days`는 월 정규화 후 '일 성분'만
  줘서 총 경과일을 크게 빗나간다(HT-3 실제 50.8일인데 `.days`는 19).
- `title IS NULL`인 stub 이슈는 제외. `duration` 랭킹은 종료된 이슈만 대상.

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
- 각 항목에 `related_changesets`(hash) / `related_issues`(issue_key)를 실어 다음 도구 호출의 진입점 제공.

### 6. `get_actor_activity`

한 사람의 커밋·PR·메시지·이슈 활동을 **2계층**(`detail`/`context`)으로 조회한다.

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
|---------|------|------|------|------|
| `identifier` | string | ✔ | — | 이름, alias(GitHub login 등), 또는 이메일 중 하나 |
| `from_time` | string | | (전체) | 조회 시작 시각 ISO-8601. 생략 시 전체 |

`limit`은 의도적으로 스키마에 없다 — LLM이 습관적으로 20을 넣어 조회 창을 옛 컷 크기로
되돌리는 것을 봉인 (조회 창은 서버 정책 `ACTOR_ACTIVITY_MAX`, 기본 100/카테고리).

- **alias/email/이름 통합 매칭**: `a.name = identifier OR identifier IN a.aliases OR`
  `ActorAlias.pd_email/pd_name = identifier`(EXISTS 서브쿼리). 개인정보(이메일·원 이름)는
  `ActorAlias`에 있어 이쪽으로 조회한다. Identity Resolution으로 통합된 Actor를 단일 식별자로 찾는다.
- 반환 구조: `name`/`aliases`/`emails`(ActorAlias의 pd_email 수집) + `totals` + `ranked_by` + `detail[]`/`context[]` +
  `issues_created`/`issues_assigned`(+`*_total`/`*_older_keys`) + `_note`.
  - `detail[]` — **인용 대상**(kind 필드로 commit/pull_request/message 구분). 카테고리별 바이트
    예산(`ACTOR_ACTIVITY_DETAIL_BUDGET`, 기본 4000자를 45/35/20%로 배분)만큼 채우고 시간순 병합.
    커밋·PR은 최신순, **메시지는 질문 임베딩×Communication 임베딩 관련도순**으로 승격(질문
    없으면 최신순 폴백, `relevance` 포함).
  - `context[]` — 나머지 활동의 시간순 stub(식별자·제목만, 본문 없음). 인용하려면 kind별 상세
    도구(commit→`get_changeset_context`, message→`get_thread_context`, pull_request→`get_pr_context`)로
    본문 조회 후 인용. 상한 `ACTOR_ACTIVITY_CONTEXT_CAP`(기본 15).
  - `totals` — 카테고리별 조회 건수. **조회 상한 도달 시 `"100+ (…)"` 문자열** — 모델이 캡된
    수치를 절대 수치로 단정하는 것 방지.
  - `issues_created`/`issues_assigned` — 최근(번호 큰) 순 `ACTOR_ACTIVITY_ISSUES_CAP`(기본 20)개
    {issue_key, title(40자)}. 잘린 오래된 이슈는 `*_older_keys`에 key 전체를 노출(드릴다운 가능).
- **종료 시각(`to`/`to_time`) 파라미터는 없다** — `from_time` 이후 전체.

### 7. `get_file_history`

파일의 변경 이력을 **질문 관련도 기반 2계층**(`detail`/`context`)으로 반환한다.
executor가 사용자 질문을 임베딩해 넘기면, 각 커밋의 `MODIFIED.embedding`과의 코사인
유사도로 재랭킹한다(질문 없거나 임베딩 실패 시 최신순 폴백).

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
|---------|------|------|------|------|
| `path` | string | ✔ | — | 파일 경로 (예: `src/auth/token.py`) |
| `limit` | integer | | (전체) | 관련도 산정 대상 커밋 상한. 보통 지정 불필요 |

- 반환 구조: `{path, total_commits, ranked_by, detail[], context[], _note}`.
  - `detail[]` — **인용 대상**. 관련도 상위 커밋을 바이트 예산(`FILE_HISTORY_DETAIL_BUDGET`,
    기본 6500자)이 되는 만큼 담는다. 커밋당 1행 — `hash`, `message`(400자 컷), `author`,
    `diff_summary`(300자 컷), `issues[]`(`issue_key`/`title`/`confidence`/`source`),
    `prs[]`(`pr_number`/`url`), `relevance`(랭킹 시). 이슈·PR 링크가 여러 개여도 행이 곱으로 불어나지 않는다.
  - `context[]` — 나머지 이력의 **시간순 개요 stub**(`hash`/`occurredAt`/`title`/`issues[issue_key]`,
    본문 없음). 전체 흐름 파악·드릴다운용. context 커밋을 인용하려면 그 hash로 `get_changeset_context`를
    호출해 본문을 조회한 뒤 인용한다(stub 요약만으로 quote 생성 금지).
  - **다 담아도 예산에 맞는 파일은 전량 `detail`**(구 동작 = 전량 인용 유지 → 나열형 recall 보존),
    예산을 넘는 파일만 관련도 상위를 `detail`로 올리고 나머지를 `context`로 내린다.
  - `ranked_by`: `relevance`(질문 임베딩 있음) 또는 `recency`(폴백).
  - 관련 노브(env): `FILE_HISTORY_DETAIL_BUDGET`/`FILE_HISTORY_DETAIL_MAX`/`FILE_HISTORY_CONTEXT_CAP`/
    `FILE_HISTORY_MAX_COMMITS` — eval 스윕용.
- **경로 fuzzy fallback**: strict 매칭이 0건이면 ① basename(`.../token.py`) ENDS WITH →
  ② stem(확장자 무관, `token`) 순으로 후보를 찾는다.
  - 후보 **정확히 1개** → 그 파일 이력(2계층)을 반환하되 결과에 `_resolved_via`(`basename_match` |
    `stem_match`) / `_resolved_path`를 부여. **evidence에는 LLM이 추정한 path가 아니라
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

- 반환: `uuid`, `display_name`, `all_aliases`, `emails`(ActorAlias의 pd_email, 중복 제거),
  활동 집계(`commit_count`/`pr_count`/`message_count`/`issue_created_count`).

### 10. `get_conflict_context`

한 커밋에 대해 Jira/Slack·GitHub/PR이 서로 다른 맥락을 줄 때 출처별로 분리해 반환한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `hash` | string | ✔ | Git commit hash |

- 반환: `issue_contexts[]`(confidence/link_source), `comm_contexts`(스레드 그룹핑),
  `pr_contexts[]`, `file_changes[]`. LLM이 다중 관점을 비교해 실제 이유를 추론하도록 설계.

### 11. `get_recent_activity`

"최근에 뭐 바뀌었어?"처럼 범위가 모호한 질문에 쓴다.

| 파라미터 | 타입 | 필수 | 기본 | 설명 |
|---------|------|------|------|------|
| `from_time` | string | ✔ | — | 시작 시각 ISO-8601. LLM이 "최근 7일" 등을 계산해 전달 |
| `to_time` | string | | (현재) | 종료 시각 ISO-8601 |
| `limit` | integer | | 30 | 최대 반환 수 |

- ChangeSet/PullRequest/Communication/Issue를 한꺼번에 occurredAt 최신순으로 반환.
  각 항목 `{type, occurredAt, actor, id, summary}` (id/summary는 타입별로 hash·pr_number·url·issue_key
  와 message·title·body로 매핑). **`from_time`은 필수다.**

### 12. `get_pr_context`

PR 번호로 시작하는 탐색.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `pr_number` | integer | ✔ | GitHub PR 번호 |

- 반환: PR 메타(`title`/`body`/`merged_at`/`created_at`/`url`/`author`) + `changesets[]` +
  `issues[]`(confidence/link_source) + `discussions`(스레드 그룹핑) + `documents[]`(연결 문서,
  `external_id` 포함 — `get_document_context`로 이어 조회) + `file_changes[]`.

### 13. `get_thread_context`

Slack 스레드를 conversation_id로 완전히 조회한다.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `conversation_id` | string | ✔ | Slack 스레드 루트 메시지 ts (예: `1773799131.000200`) |

- **Slack 전용** — GitHub Issue 등 다른 소스에는 사용하지 않는다.
- 반환: 스레드 내 메시지를 occurredAt 오름차순으로, 각 메시지에 `author`와 연결 `related_issues` 포함.
- 정렬은 RETURN alias `occurredAt`(ISO 문자열) 기준 — collect aggregation 때문에 `c.occurredAt`을
  ORDER BY에서 직접 못 쓴다(코드 주석 참고).

### 14. `run_graph_query`

전용 도구가 커버하지 못하는 질문(속성 필터·집계·다중 조건 조인·이슈 외 노드 랭킹)을 위한
범용 탈출구. 설계 배경과 측정 계획은 [`graph-query-tool.md`](graph-query-tool.md) 참고.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `cypher` | string | ✔ | 실행할 읽기 쿼리. **`project_id` 조건을 쓰지 않는다** (서버가 주입) |
| `purpose` | string | ✔ | 쿼리 의도 한 줄. 로그 전용 — 실행에 영향 없음 |

- **project_id는 쿼리 재작성으로 주입된다.** 다른 도구처럼 파라미터로 스코프되는 게 아니라,
  모든 노드 패턴 `(v:Label …)`을 `(v:Label {project_id: $project_id} …)`로 고쳐 실행한다.
  Neo4j community 에디션이라 프로젝트별 DB 분리가 불가능해 이 재작성이 격리의 유일한 보장이다.
- 검증은 **fail-closed**다. 아래는 전부 거부된다(사유는 LLM에게 전달되어 교정을 유도한다):
  쓰기·부작용 구문(`CREATE`/`MERGE`/`SET`/`DELETE`/`REMOVE`/`FOREACH`), `CALL`·`LOAD CSV`,
  `UNION`·`UNWIND`, `EXISTS{}`/`COUNT{}` 서브쿼리, 복수 문장, 라벨 없는 노드 패턴,
  화이트리스트 밖 라벨·관계, 상한 없는 가변 길이 관계(최대 5홉), MATCH 절 밖의 그래프 패턴
  (WHERE 패턴 술어·패턴 컴프리헨션 — 주입을 타지 않는 경로다).
- **MATCH 절의 노드만 스코프해도 충분한 이유**: 엣지는 프로젝트를 건너뛰지 않는다(모든 관계
  MERGE가 양끝을 project_id로 매칭). 시작 노드를 묶으면 거기서 뻗는 탐색은 프로젝트를 벗어날 수 없다.
- 실행은 읽기 전용 트랜잭션 + 5초 타임아웃. `LIMIT` 미지정 시 50을 주입하고, 200을 넘는 값은 깎는다.
- 반환은 **행 리스트**다(executor의 행 단위 잘림을 타기 위함). 반환 값에서 `embedding` 속성은
  제거되고, 다른 프로젝트 노드가 섞인 행은 후검증에서 걸러진다(재작성 실패 대비 2중 방어).
- **다른 도구와 달리 `_MIN_CONFIDENCE` 컷이 적용되지 않는다.** 시맨틱 엣지를 지날 때는
  쿼리에 `WHERE r.confidence >= 0.5`를 직접 넣어야 한다(프롬프트 스키마 카드가 안내).
- 이 도구가 호출된 질의의 응답에는 `answer_mode: "exploratory"`가 붙고, 웹 대시보드가 경고
  배너를 띄운다. 판정은 orchestrator가 **실제 호출된 도구**로 하며 LLM이 관여하지 않는다.
- 라우팅 가드: 질의당 최대 3회(`_MAX_GRAPH_QUERY_CALLS`). 중복 호출 가드는 인자 정확 일치라
  쿼리를 미세하게 고쳐 쓰는 반복을 못 잡기 때문에 별도 상한을 둔다.

### 15. `describe_graph`

값으로 거르는 쿼리를 쓰기 전에 실제 데이터 분포를 확인하는 도구.

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `label` | string | ✔ | `ChangeSet`·`PullRequest`·`Issue`·`Communication`·`File`·`Actor` 중 하나 |

- 반환: `{label, total, properties[], value_distribution{}}`. `value_distribution`은 라벨별
  주요 속성(Issue는 status·issue_type·priority·assignee·source 등)의 실제 값과 빈도 상위 20건.
- 라벨·속성·관계 **골격**은 시스템 프롬프트의 정적 스키마 카드(`explore.SCHEMA_CARD`)가 담당하고,
  이 도구는 프로젝트마다 달라지는 **값**만 조회한다 (완료 상태가 `'Done'`인지 `'완료'`인지 등).

---

## Tool Calling 조합 예시

도구를 어떻게 엮어 쓰는지 보여주는 대표 흐름.

**"결제 모듈 리팩토링 왜 했어?"** — 진입점을 모를 때 시맨틱 검색으로 시작
```
1. search_by_keyword(keyword="결제 리팩토링")
   → related_changesets: ["abc123"], related_issues: ["HT-8"]
2. get_changeset_context(hash="abc123")
   → issues: [{issue_key:"HT-8", confidence:0.85, link_source:"semantic"}]
   → communications: [{conversation_id, messages:[...]}], file_changes: [...]
3. (선택) get_thread_context(conversation_id=...) → 스레드 전체 맥락
```

**"john-dev랑 jkim@co.com 같은 사람이야?"** — Identity Resolution 확인
```
inspect_actor("john-dev")
   → all_aliases: ["GITHUB:john-dev", "JIRA:account_abc"], emails: ["jkim@co.com"]
```
