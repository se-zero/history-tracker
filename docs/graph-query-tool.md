# 범용 그래프 조회 설계 (run_graph_query + answer_mode)

전용 도구가 없는 질문에도 LLM이 그래프를 **직접 탐색해** 답하게 한다.
대가로 근거 연결이 약해질 수 있으므로, 그 경로로 나온 답임을 응답 계약에 실어 표시한다.

설계 원칙 두 가지:

1. **커버리지는 넓히되 격리는 협상하지 않는다.** `project_id`는 LLM이 쓴 쿼리에 맡기지 않고
   서버가 쿼리를 재작성해 주입한다. 환각은 감수 대상이지만 크로스 프로젝트 누출은 아니다.
2. **약한 답을 금지하는 대신 표시한다.** 목표는 환각 0이 아니라, 사용자가 **어느 답을 의심해야
   하는지** 아는 것이다. 그래서 도구 신설과 `answer_mode` 신호는 한 묶음이다 — 신호 없이
   도구만 열면 약한 답이 기존 답과 구분되지 않는다.

---

## 왜 전용 도구를 더 만들지 않는가

현재 14개 도구는 전부 **"엔티티 하나 → 그 주변"** 모양이다(`jira_key`, `hash`, `pr_number`,
`path`, `identifier`). 그래서 다음 유형이 통째로 빈다.

| 유형 | 예 | 현재 결과 |
|---|---|---|
| 속성 필터 | "미완료 Bug 이슈 목록" | `status`·`issue_type`로 거를 도구가 없음 |
| 집계·카운트 | "이슈가 몇 개고 그중 완료는?" | 전수 비교 도구는 `rank_issues`(이슈·2지표)뿐 |
| 다중 조건 조인 | "A가 만든 이슈 중 B가 논의한 것" | 2-hop을 지정할 방법 없음 |
| 비-이슈 랭킹 | "가장 많이 바뀐 파일" | File 기준 정렬 경로 없음 |

이걸 전용 도구로 하나씩 채우면 도구 수만 늘고 라우팅이 나빠진다. 전례가 있다 —
`get_timeline` 하나를 추가했을 때 그것이 전문 도구를 밀어내 case-05가 0.778→0.111로
무너졌다(`eval/improvement-log.md` 2026-07-21). **도구를 늘리는 방향은 이미 한계에 왔고,
그래서 하나의 범용 경로 + 라우팅 가드가 낫다.**

## 도구 계약

```
run_graph_query(cypher: str, purpose: str) -> list[dict]
```

- `cypher` — LLM이 작성한 읽기 쿼리. **`project_id` 조건을 쓰지 않는다**(서버가 주입).
- `purpose` — 이 쿼리로 무엇을 확인하려는지 한 줄. 로그·`answer_mode` 근거·디버깅용이며
  실행에는 영향이 없다.
- 반환은 **list** — dict(`{rows: [...]}`)로 감싸면 `executor._truncate_payload`의 행 단위
  트리머를 못 타고 문자열 컷으로 떨어져 JSON이 깨진다(case-27에서 확인된 실패).

**인용 가능성이 반환 설계의 제약이다.** `grounded_answer`의 evidence는 4타입
(commit / pull_request / issue / message)에 각각 정해진 id 형식을 요구한다. 집계 스칼라만
돌려주면 그 답은 근거 없이 뜬다. 따라서 도구 설명에 "행에 표준 식별자(`hash`, `pr_number`,
`jira_key`, `conversation_id`)와 `occurredAt`·본문을 함께 RETURN하라"를 명시하고,
집계 질의도 근거가 된 노드 id를 함께 반환하도록 유도한다.

## 안전 — 서버 측 5개 관문

Neo4j는 community 에디션이라 **프로젝트별 DB 분리가 불가능**하다. 격리는 전적으로 아래
쿼리 재작성으로 보장한다.

| # | 관문 | 내용 |
|---|---|---|
| 1 | 구문 형태 제한 | `MATCH` / `OPTIONAL MATCH` / `WHERE` / `WITH` / `RETURN` / `ORDER BY` / `SKIP` / `LIMIT` 만 허용. `CREATE`·`MERGE`·`SET`·`DELETE`·`REMOVE`·`FOREACH`·`CALL`·`LOAD CSV`·`USE`·`UNION` 거부 |
| 2 | 라벨·관계 화이트리스트 | 노드는 `ChangeSet`·`PullRequest`·`Issue`·`Communication`·`File`·`Actor` 만, 관계는 `graph-schema.md`의 10종만. **라벨 없는 노드 패턴 `()`·`(n)` 은 거부** — 무제한 스캔이자 격리 구멍 |
| 3 | project_id 주입 | 모든 노드 패턴 `(v:Label …)` → `(v:Label {project_id: $pid} …)` 로 재작성. 6개 라벨 전부 `project_id`를 가지므로 예외가 없다. 엣지는 양끝 노드가 고정되면 프로젝트를 넘을 수 없어 별도 조건이 불필요 |
| 4 | 읽기 전용 실행 | 읽기 트랜잭션(`execute_read`)으로 실행 + 트랜잭션 타임아웃 + `LIMIT` 미지정 시 서버가 주입(기본 50) |
| 5 | 결과 후검증 | 반환 행에 노드가 섞이면 `project_id` 재확인. 3이 뚫렸을 때를 대비한 2중 방어 |

관문 2·3이 격리의 본체이므로, 이 둘은 **LLM 없이 오프라인 유닛테스트로 검증한다** —
거부돼야 할 쿼리 목록과 재작성 전/후 쌍을 테이블로 두고 돌린다.

### 알려진 갭 — confidence 컷 우회

전용 도구는 시맨틱 엣지를 `_MIN_CONFIDENCE`(0.5)로 거른다(`tools/queries/_common.py`).
raw Cypher는 이 컷을 우회하므로, 0.34짜리 TRIGGERED_BY가 근거로 딸려올 수 있다.

Neo4j 5.26은 관계 패턴 술어를 지원하므로 `-[r:TRIGGERED_BY]->` →
`-[r:TRIGGERED_BY WHERE r.confidence IS NULL OR r.confidence >= 0.5]->` 재작성으로 막을 수
있다. 다만 노드 주입보다 재작성 난도가 높으므로 **1단계에서는 도입하지 않고**, 대신 시맨틱
엣지를 지나는 쿼리 결과에 `confidence`·`link_source`를 강제로 실어 모델이 구분하게 한다.
운영에서 문제가 확인되면 그때 관문 3에 추가한다(독립 측정 가능한 변경).

## 스키마 카드

모델이 쓸 수 있는 쿼리 품질은 스키마를 아는지에 전적으로 달렸다. 두 층으로 나눈다.

- **골격은 정적 주입** — 라벨·속성 이름·관계 타입·방향. 코드가 정하는 값이라 프로젝트마다
  다르지 않다. 시스템 프롬프트에 상수로 넣어 도구 호출 1회를 절약한다.
- **값 분포는 도구** — `describe_graph(label)`. `status`·`issue_type`·`source`·`channel`
  같은 enum의 **실제 값**은 프로젝트마다 다르므로 정적 문자열로 박으면 틀린다.
  `project_id` 스코프로 distinct 값과 건수를 반환한다.

정적 카드의 단일 출처는 `graph-schema.md`이며, 코드 상수와 문서가 어긋나지 않도록
`_common.EVENT_SPECS`와 같은 방식으로 한 곳에 둔다.

## answer_mode — 경로 신호

**판정은 서버가 한다. LLM 자기신고는 쓰지 않는다.** `unknown_aspects`가 이미 그 실패를
보여줬다 — `query-quality-issues.md` 케이스 3은 evidence에 **있는** 내용을 "확인 안 됨"으로
넣었고, 케이스 4는 **없는** 내용인데 unknown을 비우고 추론으로 채웠다. 양방향으로 틀린다.

- `orchestrator.run()`이 이번 턴에 호출된 도구 이름 집합을 상시 기록한다.
  (지금은 `_record_tool_call`이 `debug`가 있을 때만 기록 — eval 전용)
- `run_graph_query`가 그 집합에 있으면 `answer_mode = "exploratory"`, 아니면 `"grounded"`.
- 값은 `grounded_answer` 구조에 필드로 실린다.

**backend는 손대지 않는다.** `MessageService`가 `structured` 맵을 통째로 metadata에 저장하고
프론트 `extractStructured`가 통째로 읽으므로, 필드 추가만으로 끝까지 흐른다.
프론트는 `messageStructured.ts`에 필드 하나, `Message.tsx`에 배너, `chat.css`에 규칙 하나.

> 이미 들어간 정적 고지(`.composer-disclaimer`)와는 **다른 것**이다. 정적 고지는 항상 뜨는
> 일반 고지이고, `answer_mode`는 이 답이 약하다는 답변별 신호다. 둘 다 필요하다.

## 라우팅 가드 — 가장 큰 리스크

범용 도구는 전문 도구를 밀어낸다. 이건 가정이 아니라 이 프로젝트에서 이미 관측된 현상이다
(위 case-05). 세 겹으로 막는다.

1. **도구 설명에 negative example** — "전용 도구가 있는 질문에는 쓰지 말 것"을 예시와 함께.
   `rank_issues`가 `get_timeline` 오용을 막으려고 쓴 방식과 같다.
2. **프롬프트에 진입 조건** — 전문 도구를 최소 한 번 시도해 빈 결과이거나, 질문이
   속성 필터·집계·다중 조건 조인일 때만.
3. **호출 횟수 상한** — 질의당 3회. 중복 호출 가드는 `(name, args_json)` 정확 일치라
   쿼리 문자열을 조금만 바꿔도 뚫린다. `_MAX_ITERATIONS`(10)를 범용 쿼리 재작성으로 태우는
   것을 막는다.

**회귀 가드**: 기존 44케이스에서 `exploratory` 비율이 0에 가까워야 한다. 전문 도구로 답할 수
있는 질문에 배너가 뜨면 그건 라우팅 실패다. 이 비율 자체를 측정 지표로 기록한다.

## 측정

지금 골든셋 44건에는 **범용 질문이 0건**이다. 전부 전용 도구가 커버하는 질문이라, 코드를
고쳐도 e2e 점수가 움직이지 않는다. 분모부터 만든다.

**신규 케이스군 (case-45~)** — 유형당 최소 1건:

| 유형 | 검증 지점 |
|---|---|
| 속성 필터 | `status`·`issue_type`으로 거르고 결과를 인용 가능한 형태로 반환하는가 |
| 집계·카운트 | 숫자와 함께 근거 노드 id를 내놓는가 (스칼라만 내면 실패) |
| 다중 조건 조인 | 2-hop 경로를 맞게 구성하는가 |
| 스키마 밖 질문 | 없는 것을 **없다고** 답하는가 (case-22·23의 범용 경로 버전) |

**채점 기준을 분리한다.**

- 기존 44케이스 — 기준 그대로. **회귀 가드**(점수 유지 + exploratory ≈ 0).
- 신규 케이스 — 느슨한 바. 사실 정답률 대신 ⓐ 근거 없는 단정이 없을 것 ⓑ 답이 나왔다면
  `answer_mode=exploratory` 표기가 있을 것 ⓒ 못 찾았으면 `unknown_aspects`에 명시할 것.

판정은 기존 노이즈 플로어(recall 0.032 / precision 0.028 / 환각 0.014 / 사실 0.047 /
규칙 0.037)를 그대로 쓰고, `eval/compare.py`의 paired 비교로 본다.
기준 스냅샷은 `graph-2026-07-21.dump`를 유지한다 — 스냅샷을 섞어 비교하지 않는다.

## 작업 순서

각 단계는 독립적으로 측정 가능하도록 끊는다. 한 번에 하나만 바꾼다.

| # | 단계 | 파일 | 측정 | 상태 |
|---|------|------|------|------|
| 0 | 범용 케이스 보충 + baseline | `eval/golden/` | baseline 확보 | **미완** — 라이브 그래프 필요 |
| 1 | 안전 계층 (검증·재작성·실행) | `tools/queries/explore.py`(신규) | 오프라인 유닛테스트 — LLM 불필요 | 완료 (`test_graph_query_guard.py`) |
| 2 | 도구 등록 + 스키마 카드 + 라우팅 가드 | `definitions.py`, `executor.py`, `orchestrator.py` | 주 변경 (풀 런) | 코드 완료 · **측정 미실시** |
| 3 | `answer_mode` + 프론트 배너 | `orchestrator.py`, web-dashboard 3파일 | 표기 검증 | 완료 (`test_answer_mode.py`) |

> 실행 경로(읽기 트랜잭션·describe_graph)는 **라이브 Neo4j에서 아직 돌려보지 않았다.**
> 검증·재작성은 오프라인으로 전량 커버되지만, 드라이버 호출은 스택을 띄운 뒤 확인이 필요하다.

1단계는 LLM 없이 끝난다 — 격리가 뚫리는지는 e2e가 아니라 유닛테스트로 판정해야 한다.
2단계를 넣을 때 **라우팅 가드를 같이 넣는다.** 도구만 등록하고 가드를 미루면 기존 44케이스가
오염돼 회귀 원인 분리가 안 된다.

## 미루는 것

- **인덱스** — 이벤트 대상 노드 662개 규모에서는 측정되지 않는다(`timeline-scope.md`와 동일 근거).
  멀티 프로젝트가 한 Neo4j에 쌓이면 그때 독립 변경으로.
- **빈 결과 자동 재작성 루프** — 반복 비용이 크고 라우팅 오염을 키운다. 1차에서는 빈 결과면
  모델이 "확인되지 않음"으로 결론내게 프롬프트로 처리한다.
- **관계 confidence 재작성** — 위 "알려진 갭" 참고.

## 코드 위치

- `tools/queries/explore.py` — 구문 검증·패턴 재작성·읽기 실행 (신규)
- `tools/definitions.py` — `run_graph_query`·`describe_graph` 스키마
- `tools/executor.py` — 디스패치 case 추가 (반환 list 유지 → 기존 트리머 재사용)
- `agent/orchestrator.py` — 스키마 카드 상수, 라우팅 가드, 도구 호출 집합 기록, `answer_mode`
- `tests/unit/test_graph_query_guard.py` — 거부·재작성 케이스 테이블 (신규)
- `tests/unit/test_import_surface.py` — `MODULES`에 신규 모듈 등록
- `docs/tools.md` — 도구 목록·계약 반영
- `clients/web-dashboard/` — `messageStructured.ts` · `Message.tsx` · `styles/chat.css`

> 도구를 추가하면 `definitions.py` / `queries` / `executor.py` 세 곳의 이름이 정확히 일치해야
> 한다(`docs/tools.md` 규칙).
