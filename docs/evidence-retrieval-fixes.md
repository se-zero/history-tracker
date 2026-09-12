# 근거 회수 품질 개선 — 코드 결함 수정 계획

> 상태: **묶음 A 완료(미커밋), B~F 대기** (2026-09-12). 진행은 `feature-cycle` 스킬의 묶음 단위(계획 → 위임 → 리뷰)로 돈다.
> 원인 분석의 근거는 2026-09-10 코드 분석과 `eval/results/20260821T060820Z` 트랜스크립트 재분류다.

## 1. 배경

"이 코드가 왜 바뀌었지?"에 답하는 GraphRAG 질의에서 **근거(evidence)가 빈약하게 나오는** 원인을
코드로 추적한 결과, 도구 라우팅이 아니라 다음 네 층에 결함이 있었다.

1. **도구 조회 코드**가 그래프에 있는 근거에 도달하지 못한다 — 짧은 해시 불일치, `limit`이 관련도
   계산 전에 잘라냄, 사람 이름 정확 일치, 개요 상한이 종류 구분 없음, 검색 중복 제거 순서.
2. **결과 가공**이 8,000자에서 JSON을 중간 절단해 뒤쪽 필드(하위 이슈·논의·파일 변경)를 통째로
   버린다. 잘림 안내가 "범위를 좁혀 재호출"인데 그 도구엔 범위 인자가 없다.
3. **근거 스키마·프롬프트·검증기**가 서로 어긋난다 — 시각 없는 중첩 이슈는 인용 불가(스키마가
   `occurredAt` 필수), 프롬프트가 요구한 "제목+본문" 인용은 검증기에서 항상 탈락, 신뢰도 필터
   설명이 실제 코드와 다름.
4. **그래프 구축**에서 임베딩 실패가 Issue·Communication의 기존 임베딩을 빈 값으로 덮어쓰고,
   자동 빌드는 Communication만 보정한다.

정량 근거(2026-08-21 전수 측정, 45케이스 × 3런, 기대 근거 654슬롯):
인용 52% · 도구가 가져왔지만 미인용 28% · 어떤 도구 결과에도 없음 17% · 검증기 삭제 2%.
첫 도구 선택은 질문 유형과 일치했고 범용 Cypher 조회는 0회였다 — 라우팅은 원인이 아니다.

이 계획은 **측정 없이도 방향이 분명한 코드 결함**만 고친다. 임계값·시간 창·필터 정책처럼
eval 재측정이 전제인 항목은 §7 "하지 않는 것"으로 남기고 `docs/query-followups.md`에 등록한다.

**사용자 결정(2026-09-11)**
1. 측정 없이 방향이 분명한 코드 결함 12건만 이번 사이클(아래 표).
2. 커밋 메시지·PR 본문 검색(§7 #7)은 후속 사이클로 분리.
3. "제목+본문" 인용 탈락(#14)은 프롬프트를 "한 필드"로 고치고 검증기는 그대로 둔다.
4. backend 읽기 타임아웃 기본값은 120초.

### 이번에 고치는 12건

`#`는 2026-09-10 코드 분석의 문제 번호다. §7의 후속 항목도 같은 번호를 쓴다.

| # | 증상 | 위치 | 항목 |
|---|---|---|---|
| 1 | 짧은 해시로 커밋을 못 찾음 | `tools/queries/changeset.py` | A-1 |
| 2 | `limit`이 관련도 계산 전에 이력을 자름 | `tools/definitions.py` | A-2 |
| 3 | 사람 이름이 정확 일치라 부분 이름·대소문자가 실패 | `tools/queries/actor.py` | A-3 |
| 4 | 활동 개요 상한이 종류 구분 없어 PR·커밋이 밀림 | `tools/queries/actor.py` | A-4 |
| 5 | 키워드 검색의 스레드 중복 제거가 LIMIT 뒤 | `tools/queries/discovery.py` | A-5 |
| 8 | 문서 없는 프로젝트에서 문서 검색을 반복 | `tools/queries/document.py` | A-6 |
| 10 | PR·이슈 결과가 8,000자에서 JSON 중간 절단 | `tools/executor.py` | B-1·B-2 |
| 11 | 잘림 안내가 따를 수 없는 지시 | `tools/executor.py` | B-2 |
| 12 | 시각 없는 중첩 이슈를 인용할 수 없음 | 스키마 + 조회 4곳 | B-3·C-3 |
| 14 | 프롬프트가 요구한 인용 형식이 검증기에서 탈락 | `agent/orchestrator.py` | C-1 |
| 16 | 임베딩 실패가 기존 임베딩을 빈 값으로 덮어씀 | `graph/writes.py` | D |
| 22 | 읽기 타임아웃 60초가 답변을 통째로 버림 | backend `config/` | E |

13번(신뢰도 필터 불일치)은 필터 자체를 바꾸지 않고 **프롬프트 설명만** 실제 정책에 맞춘다(C-2).

용어: **근거(evidence)** = 답변이 인용하는 그래프 노드(커밋·PR·이슈·메시지·문서).
**2계층 결과** = 본문 있는 `detail`(인용 대상)과 본문 없는 `context`(개요 stub).
**haystack** = 이번 턴 도구 결과 문자열 전체. 검증기는 quote가 여기 연속으로 있는지 본다.

---

## 2. 묶음(체크포인트) 표

| 묶음 | 목적 | 서비스 | 병렬 | 의존 |
|---|---|---|---|---|
| A | 도구가 근거에 도달하게 — 조회 결함 6건 | ai-engine `tools/` | E와 병렬 | 없음 |
| B | 결과 크기·형태 — 절단 정책과 중첩 시각 | ai-engine `tools/` | A 뒤 순차 | A (같은 파일) |
| C | 프롬프트·검증기 정합 | ai-engine `agent/` | B 뒤 순차 | B (중첩 시각이 있어야 프롬프트가 성립) |
| D | 임베딩 안전망 — 덮어쓰기 가드 + 자동 보정 | ai-engine `graph/` | C 뒤 순차 | 없음 (파일은 안 겹치나 같은 서비스 훅 충돌 방지) |
| E | backend 읽기 타임아웃 외부화 | backend `config/` | A~D와 병렬 | 없음 |
| F | 문서 동기화 | `docs/`, `services/ai-engine/CLAUDE.md` | 마지막 | A~E 계약 확정 후 |

- 위임 단위 = 묶음 1개 = `implementer` 1회 (ai-engine·문서). E는 `config/` 패키지라 TDD 면제
  (`.claude/CLAUDE.md`), `implementer` 1회.
- 묶음마다 끝나면 `branch-review`로 그 서비스 디렉터리만 리뷰한다.
- 커밋 분리 표시: 묶음 B의 executor 공용 트리머는 도구별 캡과 **별도 커밋**으로 나눈다(공용 코드).

---

## 3. 묶음 A — 도구 도달성 (ai-engine `tools/`)

**무엇을**: 그래프에 근거가 있는데 도구가 못 찾는 6가지 경로를 고친다.
**왜**: 이 여섯은 모델이 아무리 잘 골라도 결과가 비거나 반쪽이다. 임계값과 무관한 순수 버그다.
**어떻게**: 아래 항목별로 쿼리·스키마를 고치고, 각각 오프라인 단위 테스트를 붙인다.

### A-1. 커밋 조회의 짧은 해시 허용
- `tools/queries/changeset.py` `get_changeset_context`·`get_conflict_context`: `hash: $hash` 정확
  일치를 **`cs.hash STARTS WITH $hash`** 로. 매칭 0건이면 기존 "찾을 수 없음", 2건 이상이면
  `{message, candidates:[{hash, message 첫 줄, occurredAt}]}` 반환(get_issue_context의 후보 반환
  규약 재사용). 입력이 7자 미만이면 후보 폭주 방지로 거부 메시지. 40자 전체 해시는 접두어
  해석을 건너뛴다(§6-A).
- `tools/definitions.py` 두 도구 `hash` 설명에 "앞 7자 이상 접두어 가능" 추가.
- 성공 기준: 새 테스트 `tests/unit/test_changeset_hash_prefix.py` — 접두어 매칭·후보 반환·짧은
  입력 거부가 통과(Neo4j 세션은 기존 테스트의 mock 방식 재사용).

### A-2. `get_file_history`의 `limit` 스키마 제거
- `tools/definitions.py` `get_file_history`의 `limit` 속성 삭제. `tools/executor.py` `_dispatch`에서
  `limit` 전달 제거(주석은 get_actor_activity의 같은 문구로). `files.py`의 함수 파라미터는
  내부용으로 유지(테스트가 쓴다).
- 성공 기준: `test_import_surface.py`·`test_file_history_tiers.py` 통과, definitions에 `limit`이 없음을
  확인하는 테스트 1건 추가.

### A-3. 사람 식별자 관대 매칭
- `tools/queries/actor.py` `_ACTOR_MATCH_WHERE`와 `tools/queries/issue.py` `_actor_events`의 WHERE를
  공용 헬퍼로 뽑아 **1차 정확 일치 → 2차 대소문자 무시·부분 일치**(`toLower(a.name) CONTAINS
  toLower($id)`, alias는 `SOURCE:` 접두 뒤 부분과 비교, ActorAlias `pd_name`/`pd_email`도 동일)로
  폴백. 2차에서 후보가 2명 이상이면 `{message, candidates:[{name, aliases}]}` 반환.
- `get_actor_activity`·`inspect_actor`·`get_timeline(actor)` 세 곳이 같은 헬퍼를 쓴다.
- 성공 기준: 새 테스트 `tests/unit/test_actor_match.py` — 헬퍼가 만드는 Cypher 조각과 후보 분기 검증.

### A-4. `get_actor_activity` 개요 상한을 종류별로
- `tools/queries/actor.py` `_build_activity_tiers`: `context_cap` 총량 15 대신 **종류별 상한**(기본
  커밋 10·메시지 10·PR 10, env `ACTOR_ACTIVITY_CONTEXT_CAP_PER_KIND`)으로 stub을 뽑은 뒤 시간순
  병합. `context_truncated`도 종류별로 "커밋 N건 생략" 형태.
- 성공 기준: `test_actor_activity_tiers.py`에 "메시지 100건이 있어도 PR stub이 남는다" 케이스 추가 후 통과.

### A-5. 키워드 검색 스레드 중복 제거를 LIMIT 앞으로
- `tools/queries/discovery.py` `search_by_keyword`: Communication 쿼리의 `LIMIT $top_k`를
  `LIMIT $fetch_k`로 올리고, Python 단 dedupe **후** `[:top_k]`로 자른다. Issue 쪽은 그대로.
- 성공 기준: 새 테스트 — 같은 conversation_id 6건이 상위를 채워도 결과가 top_k개 스레드.

### A-6. 문서 검색이 "문서 없음"을 구분
- `tools/queries/document.py` `search_documents`: 벡터 검색 전에 `MATCH (d:Document {project_id})
  ... LIMIT 1`로 존재만 확인해 없으면 `{"message": "이 프로젝트에는 연결된 문서 소스가 없습니다. 문서 검색을
  다시 시도하지 말고 다른 도구로 진행하세요.", "no_documents": true}` 반환.
- `agent/orchestrator.py` 프롬프트 [문서 처리] 절에 한 줄: "`no_documents`가 오면 이 질의에서
  search_documents·get_document_context를 다시 부르지 않는다."
- 성공 기준: `test_document_queries.py`에 count 0 분기 테스트 추가.

묶음 A 전체 성공 기준: `cd services/ai-engine && python -m pytest` 전부 통과.

---

## 4. 묶음 B — 결과 크기·형태 (ai-engine `tools/`)

**무엇을**: 8,000자 상한에서 JSON이 깨지지 않게 하고, 인용에 필요한 시각을 중첩 이슈에 싣는다.
**왜**: PR·이슈 결과의 문자열 절단은 하위 이슈·논의를 통째로 지운다. 스키마가 `occurredAt`을
필수로 요구하는데 커밋·PR 결과 안의 이슈에는 시각이 없어 모델이 인용을 포기하거나 지어낸다.
**어떻게**:

### B-1. 도구별 본문 캡 (get_file_history와 같은 정책)
- `tools/queries/changeset.py` 세 도구의 `file_changes[*].diffSummary`를 300자, `changesets[*].message`를
  400자로 자른다(`files.py`의 `_DIFF_SUMMARY_MAX_CHARS`·`_DETAIL_MESSAGE_MAX_CHARS`를 `_common.py`로
  옮겨 공유). `get_issue_context`의 root·descendants `changesets[*].message`도 같은 캡.
- PR `body`·이슈 `body`는 자르지 않는다(quote 원문이라 잘리면 인용이 깨진다).
- **묶음 A 리뷰 이월**: `_first_line`이 `actor.py`(`str` 반환)·`changeset.py`(`str | None`)·
  `issue.py`(cap 인자)에 세 번 정의돼 있다. 캡 상수를 `_common.py`로 옮기는 이 단계에서 함께 합친다.
- 성공 기준: 캡 적용 단위 테스트(순수 함수로 분리해 Neo4j 없이).

### B-2. executor 공용 dict 트리머 (별도 커밋)
- `tools/executor.py` `_truncate_payload`: detail/context·events 트리머가 못 잡는 **일반 dict**에
  대해, 문자열 컷으로 떨어지기 전에 **리스트 필드를 큰 것부터 뒤에서 행 단위로 줄이는** 3단계
  트리머 추가. 줄인 필드마다 `<field>_truncated: "전체 N건 중 앞 K건"` 고지. 리스트를 다 비워도
  초과하면 그때만 문자열 컷.
- 문자열 컷 안내의 "더 좁은 범위로 다시 호출" 문구를 "이 결과는 불완전합니다. 잘린 필드는
  개별 상세 도구(커밋→get_changeset_context, 이슈→get_issue_context)로 조회하세요"로 교체.
- 성공 기준: `test_executor_truncation.py`에 dict-리스트 트림 케이스(JSON 유효·고지·순서 보존) 추가.

### B-3. 중첩 이슈·PR에 시각 실기
- `get_changeset_context`·`get_conflict_context`·`get_pr_context`의 `issues[*]`,
  `get_file_history`의 `detail[*].issues[*]`, `get_issue_context`의 `descendants[*]`에
  `created_at`·`closed_at`(`_common.normalize_time`으로 UTC 정규화)을 추가. `get_issue_context`
  root에도 `created_at`·`closed_at` 추가(현재 `occurredAt`만 있음).
- 성공 기준: `test_changeset_pr_document_external_id.py` 방식으로 RETURN 절 키 존재 테스트.

묶음 B 성공 기준: pytest 전부 통과 + B-2는 별도 커밋으로 분리 가능한 diff.

---

## 5. 묶음 C — 프롬프트·검증기 정합 (ai-engine `agent/`)

**무엇을**: 프롬프트가 코드와 다르게 말하는 세 곳을 코드에 맞춘다.
**왜**: 프롬프트가 요구한 인용 형식을 검증기가 거부하면 정당한 근거가 삭제된다. 신뢰도 설명이
틀리면 모델이 헤지해야 할 항목을 확정 사실로 쓴다.
**어떻게**:

### C-1. 인용 형식: "제목+본문"을 "제목 또는 본문"으로
- `agent/orchestrator.py` [증거 인용 규칙]과 `_GROUNDED_ANSWER_SCHEMA.evidence.quote` description의
  "pr title+body / issue title+body"를 "pr title 또는 body 중 한 필드 / issue title 또는 body 중
  한 필드"로. 이유를 주석에: JSON에서 두 필드가 떨어져 있어 붙여 쓰면 검증기가 삭제한다.
- `_canon`에 `\\t` 이스케이프 처리 추가(`\\r`·`\\n`과 동일).
- 검증기(`_drop_unverified_quotes`)의 연속 일치 규칙은 **그대로 둔다** — 완화하면 서로 다른 두
  문장을 이어 붙인 인용까지 통과해 환각 가드가 약해진다(사용자 결정 ③).
- 성공 기준: `test_quote_validation.py`에 탭 포함 인용 통과 케이스 추가.

### C-2. 신뢰도 필터 설명을 실제 정책으로
- 프롬프트의 "`__MIN_CONF__` 미만 엣지는 쿼리 단에서 이미 차단"을 "커밋→이슈(TRIGGERED_BY)·
  문서 연결은 `__MIN_CONF__` 미만이 차단되지만, 커밋↔대화(REFERENCE)·이슈↔대화(DISCUSSED_IN)는
  그대로 실리므로 `confidence`가 있으면 값을 보고 판단"으로. `link_source`가 없는 REFERENCE(대화)
  항목에 `link_source: ref.source`를 함께 RETURN(changeset.py 세 곳).
- 필터 자체는 바꾸지 않는다(§7 #13 참조).
- 성공 기준: 프롬프트 문자열 테스트(`test_internal_term_leak.py` 방식) 1건.

### C-3. 중첩 이슈 인용 규칙
- [증거 인용 규칙]에 "커밋·PR 결과 안의 `issues[*]`를 인용할 때 `occurredAt`은 그 항목의
  `created_at`(event_meaning=issue_created) 또는 `closed_at`(issue_closed)을 쓴다. 둘 다 없으면
  get_issue_context로 조회한 뒤 인용한다" 추가.

### C-4. `get_timeline` actor 후보 안내 (묶음 A 리뷰 이월)

묶음 A가 actor 스코프에도 `scope.candidates`를 실어 보내게 했는데, 프롬프트 [시간순 질문 처리]
절은 `candidates`의 원인을 경로와 이슈 키로만 열거한다. actor 후보가 와도 모델이 무엇을 해야
할지 모른다.

- 그 절에 actor 스코프를 추가하고, 표시 이름이 같을 수 있으니 alias로 재호출해도 된다고 명시한다
  (도구 응답의 안내 문구와 같은 취지).
- 성공 기준: 프롬프트 문자열 테스트 1건.

묶음 C 성공 기준: pytest 통과.

---

## 6. 묶음 D·E — 임베딩 안전망, backend 타임아웃

### 묶음 D — 임베딩 안전망 (ai-engine `graph/`)

**무엇을**: 임베딩 실패가 기존 값을 지우지 못하게 하고, 자동 빌드가 Issue·ChangeSet 누락분도 보정한다.
**왜**: 임베딩 없는 이슈는 키워드 검색과 시맨틱 링크 양쪽에서 사라진다. 지금은 이슈 갱신 이벤트
한 번의 실패로 멀쩡하던 임베딩이 빈 값이 되고, 복구는 운영자가 admin 엔드포인트를 손으로 불러야 한다.
**어떻게**:
- `graph/writes.py` `upsert_issue`·`upsert_communication`·`upsert_document_section`:
  `upsert_changeset`과 같은 `CASE WHEN size($embedding) > 0 THEN $embedding ELSE <기존> END`.
- `graph/postprocess.py` `run_postprocess_sequence`: 1단계 `backfill_communication_embeddings` 옆에
  `backfill_issue_embeddings(link_store)`·`backfill_changeset_message_embeddings(ref_store)`를 추가
  (둘 다 이미 존재 — `issue_linker.py`, `reference_builder.py`). 결과 dict에 `backfilled_issues`·
  `backfilled_changesets` 추가. backend가 읽는 `backfilled` 계약은 그대로 둔다.
- 성공 기준: `test_postprocess_sequence.py`에 두 backfill 호출 순서(빌더 전) 테스트 추가; writes는
  Cypher 문자열에 CASE가 포함되는지 확인하는 테스트.

### 묶음 E — backend 읽기 타임아웃 외부화 (backend `config/`)

**무엇을**: `AiEngineConfig.READ_TIMEOUT` 60초 상수를 프로퍼티 `ai.engine.read-timeout-seconds`
(기본 120)로 뺀다.
**왜**: 질의가 60초를 넘으면 backend가 고정 폴백 답변으로 바꿔 근거가 통째로 사라진다. 반복
10회에 드릴다운이 많은 질문은 이 벽에 닿는다. 최근 전수 측정의 최대 지연이 50초였고 반복 상한을
다 쓰면 그 두 배가 현실적이다. ai-engine 쪽엔 전체 데드라인이 없다.
**어떻게**: `@Value("${ai.engine.read-timeout-seconds:120}")`, `application.yaml`과 compose `.env`
포워딩에 키 추가. `config/` 패키지라 TDD 면제. 성공 기준: `./gradlew test` 통과, 기동 로그에 값 출력.

### 묶음 F — 문서 동기화

- `docs/tools.md`: 공통 규칙의 TRIGGERED_BY 컷오프 서술을 C-2와 맞춤, 2·10(해시 접두어·후보),
  5(dedupe 순서), 6(종류별 개요 상한), 7(`limit` 제거), 12(캡)·잘림 안내 문구.
  **묶음 A 리뷰 이월**: L273이 사라진 상수 `ACTOR_ACTIVITY_CONTEXT_CAP`(기본 15)를 안내한다 →
  `ACTOR_ACTIVITY_CONTEXT_CAP_PER_KIND`(기본 10, 종류별)로 정정.
- `services/ai-engine/CLAUDE.md`: 새 env 노브(`ACTOR_ACTIVITY_CONTEXT_CAP_PER_KIND`), 자동 빌드가
  세 종류 임베딩을 보정한다는 한 줄.
- `docs/query-followups.md`: §7 "하지 않는 것"을 후속 TODO로 등록.
- `docs/deployment.md` 또는 backend CLAUDE.md: 새 프로퍼티 한 줄.
- 이 문서 상단 상태를 "완료"로 갱신.

---

## 6-A. 묶음 A 리뷰 결과 (2026-09-12)

`branch-review` 단계 리뷰: Critical 0 · Major 0 · Minor 8 · Note 2 → 🟢. 테스트 929개 통과
(묶음 A 착수 전 기준선 898).

**반영한 것** — Actor 메타 조회의 None 가드 복원(연동 해제가 두 쿼리 사이에 끼면 `dict(None)`),
모호 안내에 alias 병기(표시 이름이 같은 노드가 여럿이면 이름으로는 영원히 모호하다),
후보 목록 6건 잘림 고지(고지가 없으면 모델이 목록을 전부로 믿는다), 40자 전체 해시는 접두어
해석을 건너뜀(타임라인·파일 이력이 넘기는 형태이고 `STARTS WITH`가 복합 인덱스를 못 타면
전체 스캔), 문서 존재 확인을 전수 카운트에서 `LIMIT 1`로.

**현행 유지** — 부분 일치에 최소 길이 가드를 두지 않는다. `LIMIT 6`이 이미 후보 폭주를 막고,
한두 글자가 정당한 이름인 언어가 있어 해시(A-1의 7자)와 같은 기준을 적용할 수 없다.

**이월** — `_first_line` 3중 정의는 B-1, `get_timeline` actor 후보 프롬프트는 C-4,
`docs/tools.md` 상수 참조는 묶음 F.

---

## 7. 하지 않는 것 (후속 — eval 재측정 또는 별도 설계가 전제)

번호는 2026-09-10 코드 분석의 22개 문제 번호다.

| # | 항목 | 이유 |
|---|---|---|
| 6 | 검색 threshold 0.30 재조정 | 3-large 점수 분포를 라이브 그래프에서 재고 스윕해야 한다 |
| 7 | ChangeSet 벡터 인덱스 + PR 임베딩 | 인덱스·수집 경로·백필이 붙는 기능 단위. 이번 12건이 먼저 들어가야 검색 결과가 잘리지 않고 인용된다 — 후속 사이클 |
| 9 | 멀티테넌트 over-fetch | 규모 문제. 테넌트 수가 늘 때 |
| 13 | REFERENCE·DISCUSSED_IN에 0.5 필터 통일 | recall에 직접 영향 — 스윕 필요. 이번엔 설명만 정정 |
| 15 | 나열형 질문의 detail 예산·반복 상한 | 예산 스윕이 전제 |
| 17 | Slack 룰 필터 7자·삭제 정책 | slack_filter_eval 재측정 전제 |
| 18 | REFERENCE ±5일, TRIGGERED_BY 후보 제외 규칙 | 엣지 레벨 eval 전제 |
| 19 | 파일 이름 변경 추적 | File 노드 표현 설계(별칭 vs RENAMED_TO) 필요 |
| 20 | 커밋 메시지의 `#123` 추출 | PR 번호와 구분 불가 — stub 오염 위험. GitHub Issue 소스 설계와 함께 |
| 21 | 대형 diff placeholder 임베딩 | 요약 전략 변경 |

---

## 8. 검증 (end-to-end)

1. 단위: `cd services/ai-engine && python -m pytest` / `cd services/backend && ./gradlew test`.
2. 실기동: `cd infra/docker && ./dev.sh up -d --build` 후 `/query`에 `include_debug=true`로 직접 질의
   (내부 토큰 헤더 필요, `docs/measurement.md` 런북). 확인할 질문 유형:
   - "8ca1eb6 커밋의 변경사항" → get_changeset_context가 비지 않음
   - "junsu가 뭐 했어" → 부분 이름으로 Actor 도달, PR stub이 개요에 남음
   - "PR #20은 어떤 문제를 해결했어" → 결과가 유효 JSON, `issues[*].created_at` 존재, 이슈가 근거에 등재
   - 문서 소스 없는 프로젝트에서 "왜" 질문 → search_documents 1회 후 재시도 없음
3. 회귀 측정(선택): `eval/runner.py --cases case-01,case-02,case-06,case-07,case-10,case-12,case-24,
   case-25,case-28` × 3런 → `grader.py` → `compare.py`로 `20260821T060820Z`와 비교. 그래프
   스냅샷이 다르면 "측정 장치 차이" 경고가 뜨므로 절대값이 아니라 해당 케이스의 recall 방향만 본다.
