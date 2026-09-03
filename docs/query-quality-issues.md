# GraphRAG 쿼리 품질 이슈 분석

분석 기준일: 2026-05-20

---

## 케이스 1 — "HT-5는 어떤 작업이고 어떻게 마무리됐어?"

### 문제

**summary에 Slack 메시지 인용, evidence에는 미등재**

summary에 "관련된 슬랙 메시지에서는 API 테스트 결과를 정리하여 노션에 올렸다는 내용이 언급되었습니다"라고 적혔으나 evidence[]에는 HT-5 이슈 항목만 있고 message 타입 항목이 없다.

- 도구 응답에 슬랙 메시지가 포함되어 있었음에도 LLM이 evidence 슬롯에 넣지 않음
- 시스템 프롬프트 규칙("summary에 적힌 사실은 evidence[]에 매핑되어야 함") 위반
- JSON Schema가 *구조*는 강제하지만 summary↔evidence의 *의미적 매핑*은 강제 불가

### 개선 방안

1. **프롬프트 강화** — `orchestrator.py` `_SYSTEM_PROMPT`의 증거 인용 규칙에 다음 구체 규칙 추가:
   > "summary에서 슬랙/메시지/대화를 언급하면 evidence[]에 `type: message` 항목이 반드시 1건 이상 존재해야 한다."

2. **포스트 검증 (선택)** — `_render_structured` 직전에 summary에 '슬랙/메시지/대화' 키워드가 있는데 evidence에 `type=message`가 없으면 재생성 요청. 모델 호출 1회 추가 비용.

---

## 케이스 2 — "HT-3 작업의 배경과 변경 내용을 알려줘"

### 문제 1 — evidence.author가 null인데 summary에 작성자 이름 등장

summary에 "정세영이 생성하였고"라고 쓰면서 evidence의 `author` 필드는 `null`.

**원인:**
- `get_issue_context` 도구 응답 키는 `creator`인데, evidence 스키마 필드는 `author`
- 필드명이 달라 LLM이 보수적으로 null 처리 (ChangeSet·Communication은 응답에 `author` 키를 직접 가짐)
- event_meaning이 `issue_closed`일 때 "닫은 사람 ≠ 생성자"로 추론해 null을 선택했을 가능성도 있음

**개선 방안:**
- `queries.py` Issue 쿼리에 별칭 추가:
  ```cypher
  creator.name AS creator,
  creator.name AS author   -- evidence 스키마 키와 통일
  ```
- `orchestrator.py` evidence author 필드 description 보완:
  > "Issue의 경우 도구 응답의 creator를 author로 사용. event_meaning이 issue_closed/issue_updated여도 creator 값을 그대로 등재."

---

### 문제 2 — evidence 없는 일반론이 summary에 삽입 (환각)

"주요 목적은 데이터 간의 의미적 관계를 명시하는 지식 그래프를 구축하여 …"라는 문장이 HT-3 도구 결과 어디에도 없는 프로젝트 전체 설명(project_context 또는 훈련 데이터 기반 추정)이다.

**개선 방안:**
- 시스템 프롬프트 강화:
  > "project_context에서 가져온 도메인 설명은 도구 탐색 키워드 선택에만 활용. summary에 project_context 내용을 직접 인용 금지."

---

### 문제 3 — `descendants[]` 무시로 하위 이슈 커밋·Slack 논의 누락

HT-3는 직접 연결 커밋이 없지만 하위 이슈들에는 명시적 커밋과 관련 Slack 논의가 있다. `get_issue_context`는 `CHILD_OF*1..5` 재귀로 이 데이터를 `descendants[]`에 담아 반환하는데, LLM이 root 레벨만 보고 "커밋 없음, Slack 없음"으로 결론내렸다.

**실제 도구 응답 구조:**
```
{
  "issue_key": "HT-3",
  "changesets": [],       ← 직접 커밋 없음 → LLM이 여기서 판단 종료
  "discussions": [],
  "descendants": [
    { "issue_key": "HT-3-1", "changesets": [...], "discussions": [...] },
    ...                   ← 실제 데이터가 여기 있었으나 무시됨
  ]
}
```

**개선 방안:**
- 시스템 프롬프트에 다음 규칙 추가:
  > "get_issue_context 결과에 `descendants[]`가 있으면 반드시 순회해 커밋·PR·논의를 종합한다. root의 changesets/discussions가 비어 있어도 descendants가 있으면 '연결 없음'으로 결론 내리지 말 것."
- 구조적 보강(선택): `get_issue_context` 응답에 `all_changesets`, `all_discussions` 집계 필드를 추가해 LLM이 descendants를 지나치지 못하게 평탄화.

---

### 문제 4 — `get_timeline` 미호출로 타임라인 공백

"배경과 변경 내용"처럼 이슈 진행 과정을 묻는 질문에서 `get_timeline`을 호출하지 않아 커밋·PR·Slack 이벤트 순서가 답변에 반영되지 않음.

**개선 방안:**
- 시스템 프롬프트 도구 사용 가이드에 추가:
  > "이슈의 '진행 과정', '변경 내용', '어떻게 마무리됐는지' 류 질문에는 get_issue_context 이후 반드시 get_timeline을 추가 호출."

---

### 문제 5 — 같은 노드를 두 개의 evidence로 중복 등재

HT-3 Issue 노드 하나를 title quote / body quote로 분리해 evidence 항목 2개로 만들었다. 같은 노드의 두 필드는 한 항목의 quote 안에서 합쳐야 한다.

**개선 방안:**
- 시스템 프롬프트에 추가:
  > "동일 노드(같은 type·id)는 evidence에 1건만 등재. title과 body를 모두 인용하려면 한 항목의 quote 안에 합쳐서 인용."

---

### 문제 6 — event_meaning과 occurredAt 필드 불일치

evidence의 `occurredAt`이 `Issue.occurredAt`(최종 업데이트 시각)인데 event_meaning을 `issue_closed`로 라벨했다. 시스템 프롬프트의 타임스탬프 의미 사전에 따르면 `Issue.occurredAt` → `issue_updated`가 맞다.

`get_issue_context`가 `occurredAt`만 반환하고 `closedAt`은 반환하지 않아 모델이 status=완료를 보고 임의로 `issue_closed`로 추정한 정황.

**개선 방안:**
- `queries.py` Issue 쿼리에 `closedAt` 필드 추가 반환:
  ```cypher
  toString(i.closedAt) AS closedAt
  ```
- 그러면 LLM이 `closedAt IS NOT NULL` 여부로 명확히 분기 가능.

---

## 케이스 3 — "HT-34에서 도입한 Neo4j tool calling 구조를 설명해줘"

### 문제 1 — unknown_aspects가 evidence와 자기모순

"구체적인 도구 호출 로직과 세부 구현 내용은 그래프에서 확인되지 않음"이라고 적었는데, evidence의 커밋 `9ef0504a`에는 구조 전체가 이미 기술되어 있다:

```
- tools/definitions.py: 13개 도구 OpenAI Function schema 정의
- tools/queries.py: 도구별 Neo4j Cypher async 함수 구현
- tools/executor.py: tool name + args → Cypher 실행 후 JSON 반환
- agent/orchestrator.py: LLM tool calling 루프 (최대 10회, gpt-4o-mini)
- main.py: POST /query 엔드포인트 추가
```

그래프에 없는 정보가 아니라 evidence에 있는 정보를 unknown_aspects에 잘못 넣은 것. 시스템 프롬프트의 unknown_aspects 정의("그래프에서 근거를 찾지 못한 측면들")를 위반.

**개선 방안:**
- 시스템 프롬프트에 추가:
  > "unknown_aspects에는 도구 호출 결과 전체를 탐색했음에도 답을 찾지 못한 측면만 적는다. evidence[]에 이미 등재된 내용과 관련된 사항은 unknown_aspects에 넣지 않는다."

---

### 문제 2 — summary가 evidence를 합성하지 않음

현재 summary: "여러 컴포넌트로 구성되어 있으며, 주로 자연어 질문 처리를 위한 기능을 포함하고 있습니다."

질문이 **구조 설명**을 요청했고, evidence에는 5개 레이어의 구체적인 구현 내용이 있었다. 모델이 커밋 메시지를 quote로 *인용*은 했지만 그것을 *이해해서 종합*하지는 않았다. summary가 되어야 할 내용:

> "definitions.py(13개 도구 스키마) → queries.py(Cypher 함수) → executor.py(디스패치·임베딩) → orchestrator.py(LLM 루프) → main.py(엔드포인트)의 5레이어 구조. backend는 QueryService가 RestClient로 /query를 호출해 연동."

이 응답의 근본 실패는 **데이터 수집(evidence)은 잘 됐는데 합성(summary)을 못 한 것**으로, 케이스 1·2의 환각/누락과는 다른 패턴이다.

**개선 방안:**
- 시스템 프롬프트에 추가:
  > "'구조', '설명', '어떻게 동작하나' 류 질문에서는 evidence의 커밋 메시지·PR 본문을 단순 나열하지 말고, 레이어·흐름·역할 분담을 재구성해 summary에 서술한다."

---

### 문제 3 — PR id 포맷 불일치 (minor)

evidence의 PR id가 `"19"`인데 스키마 규칙은 `"#19"` 형식. 사소하지만 반복되면 일관성이 깨진다.

**개선 방안:**
- `orchestrator.py` evidence id 필드 description의 예시를 더 강조:
  > "pull_request → '#번호' 형식 필수. 숫자만 적지 말 것 (올바른 예: '#19', 잘못된 예: '19')."

---

## 케이스 4 — "HT-35 벡터 인덱스 자동 생성이 왜 필요했어?"

### 문제 1 — "왜"를 물었는데 "무엇"으로 답함

질문은 도입 동기·배경을 요구했는데, summary의 대부분은 구현 방법(서버 시작 시 `ensure_vector_indexes()` 호출, 커밋·이슈 임베딩 관리)을 설명한다. "왜"에 해당하는 문장은 summary 마지막 한 줄뿐이고, 그마저도 evidence 기반이 아니다.

---

### 문제 2 — 근거 없는 이유 추론 (환각)

summary: "이러한 자동 생성이 필요한 이유는 기본적인 멱등 처리를 통해 인덱스의 일관성을 유지하고, 이후의 쿼리 성능을 향상시키기 위함입니다."

| 주장 | evidence 근거 |
|---|---|
| "멱등 처리를 통해 일관성 유지" | 커밋에 "IF NOT EXISTS로 멱등 처리" 있음 — 구현 방법이지 도입 이유가 아님 |
| "쿼리 성능을 향상시키기 위함" | evidence 어디에도 없음 — 환각 |

---

### 문제 3 — unknown_aspects가 비어있어 신뢰성 과장

"왜 필요했어?"의 진짜 답(수동 생성 불편, 인덱스 누락 장애, 배포 자동화 요건 등)은 그래프에 없다. 그런데 `unknown_aspects: []`로 비워놔서 "다 알고 답했다"는 인상을 준다.

케이스 3과 반대 방향의 오류:
- 케이스 3: **있는** 내용을 unknown_aspects에 넣음
- 케이스 4: **없는** 내용인데 unknown_aspects를 비우고 summary에 추론으로 채움

unknown_aspects에 들어가야 했을 내용:
> "벡터 인덱스 자동 생성이 필요해진 구체적인 배경(수동 생성 문제, 장애 경험, 배포 요건 등)은 그래프에서 확인되지 않음."

**개선 방안:**
- 시스템 프롬프트에 추가:
  > "'왜', '이유', '배경' 류 질문에서 도구 결과에 명시적 동기가 없으면 unknown_aspects에 반드시 명시. 구현 detail(IF NOT EXISTS, lifespan 호출 등)을 도입 이유로 재포장하지 말 것."

---

### 문제 4 — commit id 6자리 (minor)

`f2cbe4` — 규칙은 7자리.

---

## 케이스 5 — "HT-36이 슬랙에서 가장 많이 논의됐는데 결론은 뭐였어?"

전반적으로 잘 된 응답이나 두 가지 문제가 있다.

### 문제 1 — 같은 스레드 메시지를 개별 evidence로 분해

실제 Slack 구조: 메시지 3개(1778586053, 1778590761, 1778591818)는 동일 스레드, 마지막 1개(1778834642)는 별도 스레드.

`_group_communications_by_thread`는 `conversation_id`(= 스레드 루트 ts)로 그룹핑하므로, 도구 응답에는 3개 메시지가 하나의 그룹으로 묶여서 왔을 것이다. 그런데 evidence에는 3개가 각각 다른 id로 찍혔다:

```
1778586053.322069  ← 루트 메시지 ts (= conversation_id)
1778590761.356869  ← 리플라이 메시지 ts  ← 스키마 위반
1778591818.220329  ← 리플라이 메시지 ts  ← 스키마 위반
```

LLM이 그룹 안의 개별 메시지 `occurredAt`을 id로 꺼내 쓴 것. 스키마 규칙(`message → conversation_id`) 위반이고, 하나의 스레드를 3개 별개 출처로 분해해 스레드 경계 정보가 소실됐다.

올바른 처리:
- 같은 `conversation_id` 그룹이면 evidence를 **1건**으로 등재하고 id = `conversation_id`(루트 ts) 사용
- 또는 `get_thread_context("1778586053.322069")` 호출로 스레드 전체 조회 후 1건 등재

시스템 프롬프트에 이미 "한 스레드의 대표 메시지만 보이면 `get_thread_context`를 호출하라"는 규칙이 있으나 지켜지지 않았다([orchestrator.py:143](../services/ai-engine/agent/orchestrator.py#L143)).

**개선 방안:**
- 시스템 프롬프트 강화:
  > "evidence에 등재할 message id는 반드시 해당 메시지의 conversation_id(스레드 루트 ts)를 사용. 리플라이 메시지의 개별 ts를 id로 쓰지 말 것."
  > "도구 응답에 같은 conversation_id를 가진 메시지 그룹이 있으면 evidence 1건으로 등재하고, 전체 흐름이 필요하면 get_thread_context를 호출."

---

### 문제 2 — 잘린 메시지를 summary에서 완성 (할루시네이션)

`1778591818` 메시지 quote: "그래프 생성방안 후보들 적어놓았던거 이젠 확정해서 코드 짜가지고 수정하면 좋을거 같아서"

"~서"로 끊겨 문장이 불완전한데, summary에서 "코드를 수정할 계획도 **제안했습니다**"로 완성했다. 이 메시지는 동일 스레드 안에 있으므로 `get_thread_context`를 호출했다면 전후 문맥을 확인할 수 있었다. 스레드를 조회하지 않은 채 잘린 텍스트를 임의로 완성한 것이 할루시네이션의 원인.

**개선 방안:**
- 위 문제 1의 `get_thread_context` 호출 규칙이 지켜지면 자연히 해소됨.
- 프롬프트 보완:
  > "quote가 '~서', '~고', '~며' 등 접속형으로 끊기면 문장을 완성하지 말고 '…(이하 생략)'으로 처리하거나 get_thread_context로 전문을 조회."

---

## 케이스 6 — "GitHubNormalizer는 어떤 이슈들 때문에 바뀌어 왔어?"

### 문제 1 — 도구 선택 오류: `search_by_keyword` 대신 `get_file_history`를 써야 했음

질문은 파일 변경 이력과 그 원인 이슈를 묻는다. 올바른 접근:

| | 도구 | 반환하는 것 |
|---|---|---|
| 써야 했던 것 | `get_file_history("GitHubNormalizer")` | GitHubNormalizer를 수정한 커밋 + 각 커밋의 TRIGGERED_BY 이슈 |
| 실제 쓴 것 | `search_by_keyword("GitHubNormalizer")` | "GitHubNormalizer"와 텍스트/의미적으로 유사한 이슈 |

`search_by_keyword`는 파일을 **언급한** 이슈를 찾고, `get_file_history`는 파일을 **실제 변경한 커밋에 연결된** 이슈를 찾는다. 둘은 인과관계가 다르다.

결과적으로 evidence 세 건(HT-50, HT-46, HT-41)이 GitHubNormalizer를 실제로 바꾼 이슈인지 보장이 없다:
- HT-50: GitHubNormalizer 질문 실패가 동기였지만, 실제 변경은 `get_file_history`에 fuzzy 매칭 추가 — GitHubNormalizer 자체를 바꾼 이슈가 아닐 가능성
- HT-46: GraphRAG 전체 품질 개선 — GitHubNormalizer와 직접 연관인지 불명확
- HT-41: GitHub 파이프라인 수정 — 관련 있을 수 있으나 파일 변경 이력으로 확인된 것이 아님

**개선 방안:**
- 시스템 프롬프트 도구 사용 가이드에 추가:
  > "파일·클래스·함수가 '어떤 이슈 때문에 바뀌어 왔는지' 물으면 `search_by_keyword` 대신 `get_file_history`를 먼저 호출. `get_file_history` 결과의 `issue_key`가 직접 연결된 이슈이며, `search_by_keyword`는 파일을 언급한 이슈를 찾을 뿐 변경 인과관계를 보장하지 않는다."

---

### 문제 2 — summary 환각

- **"Slack 및 Jira의 데이터 처리 관련 이슈들"** — evidence 세 건 중 Slack이나 Jira 데이터 처리를 언급하는 것은 하나도 없음. 완전한 허구.
- **"기초 데이터의 형식과 쿼리 접근법 개선을 통해 사용자 요청에 대한 정확도를 높이고자 하였습니다"** — evidence 어디에도 없는 일반론.

---

### 문제 3 — unknown_aspects 모순 (케이스 3 패턴 반복)

"코드 변경에 대한 명확한 이유와 배경은 확인되지 않음" — 그런데 HT-50 quote에는 배경이 명시적으로 적혀 있다("GitHubNormalizer 질문에서 LLM이 .py로 추정 → strict path 매칭 실패"). evidence에 있는 내용을 unknown_aspects에 기입한 케이스 3 패턴.

---

## 케이스 7 — "PR #18은 어떤 작업이고 어떤 이슈와 연결돼?"

전반적으로 좋은 응답. PR #18 evidence는 포맷·author·quote 모두 완벽하고, summary도 질문의 두 파트("어떤 작업 + 어떤 이슈")를 정확히 답했다. 단, HT-37 evidence에 구조적 문제가 있다.

### 문제 1 — `occurredAt: "unknown"` — 스키마 위반

HT-37 evidence의 `occurredAt`이 `"unknown"` 문자열. ISO-8601 규칙 위반이고 `event_meaning: issue_closed`와도 모순이다.

**근본 원인은 `get_pr_context` 쿼리 갭.** 이슈를 collect할 때 타임스탬프와 creator를 반환하지 않는다([changeset.py의 `get_pr_context`](../services/ai-engine/tools/queries/changeset.py#L157) — 분석 당시에는 `tools/queries.py:886-902`였고, 이후 `tools/queries/` 패키지로 분해됐다):

```cypher
collect(DISTINCT {
    issue_key: i.issue_key, title: i.title,
    status: i.status,
    confidence: tb.confidence,
    link_source: tb.source
    -- occurredAt, closedAt, createdAt, creator 없음
}) AS issues
```

케이스 2의 `get_issue_context` creator 누락과 동일 유형의 도구 응답 갭.

**개선 방안 — `queries.py` `get_pr_context`:**
```cypher
collect(DISTINCT {
    issue_key:   i.issue_key, title: i.title, status: i.status,
    occurredAt: toString(i.occurredAt),
    closedAt:   toString(i.closedAt),
    creator:    creator.name,   -- OPTIONAL MATCH (creator:Actor)-[:CREATED]->(i) 추가 필요
    confidence: tb.confidence,  link_source: tb.source
}) AS issues
```

---

### 문제 2 — `get_issue_context` 추가 호출로 보완 가능했으나 안 함

모델이 HT-37 `issue_key`를 알고 있었으므로 `get_issue_context("HT-37")`를 호출해 타임스탬프·creator를 채울 수 있었다. 그 대신 "unknown"으로 마무리한 것은 LLM 판단 실패.

**개선 방안 — 프롬프트:**
> "evidence의 `occurredAt`이 없거나 불명확하면 null을 쓰되, issue 타입이면 `get_issue_context`를 추가 호출해 실제 타임스탬프로 채운다. 'unknown' 문자열은 절대 사용 금지."

---

## 케이스 8 — "프로젝트 초기에 데이터 수집 파이프라인 구조는 어떻게 정해졌어?"

전반적으로 무난. 모호한 "프로젝트 초기" 질문에서 멀티소스(HT-7, Slack, HT-3) 응답을 구성한 것은 좋으나 세 가지가 아쉽다.

### 문제 1 — "어떻게 정해졌어"를 "무엇이 정해졌어"로 대체 (케이스 4 패턴 반복)

질문은 의사결정 **과정**을 묻는데, summary는 결론(3개 플랫폼 수집, 페이지네이션, 정규화 API)만 나열한다. Slack 메시지 한 건도 "pr 날렸어"라는 경위 보고이지, 구조를 어떻게 논의했는지는 아니다. `get_thread_context`로 해당 스레드를 확인하거나 더 이른 시기의 Slack 논의를 추가 탐색했어야 했다.

unknown_aspects에 "의사결정 과정 불명확"이 있는 건 적절하지만, 적극적 탐색 없이 바로 unknown으로 처리한 것은 아쉽다.

---

### 문제 2 — `event_meaning: issue_created` 신뢰성 의문

`search_by_keyword`는 `i.occurredAt`(최종 업데이트 시각)을 반환한다. 모델이 "프로젝트 초기" 맥락을 보고 `issue_created`로 라벨했으나, 실제로 `createdAt`을 받았는지 `occurredAt`을 잘못 라벨한 건지 불명확하다.

HT-3의 경우 케이스 2에서 동일 타임스탬프(`2026-05-04T01:25:55.805Z`)에 `issue_closed`를 붙였는데, 이번엔 같은 값에 `issue_created`를 붙였다. **동일 타임스탬프에 케이스마다 다른 event_meaning이 붙는 것 자체가 이 필드 전반의 신뢰도 문제를 드러낸다.**

`get_timeline`을 호출했다면 `createdAt`과 `closedAt`이 분리되어 명확했을 것.

---

### 문제 3 — `author: null` 반복

`search_by_keyword` 결과에 creator 정보가 없어 두 이슈 모두 author null. 케이스 2, 7에서 반복되는 구조적 문제.

---

## 케이스 9 — "Slack 데이터 정규화 작업이 처음 어떻게 만들어졌는지 시간 순으로 정리해줘."

### 문제 1 — event_meaning 세 건 모두 오류 (케이스 8 구조 문제 심화 확인)

`get_issue_context`가 반환한 `occurredAt`이 실제로는 `closedAt`(완료일)이었는데, 모델이 "시간순", "처음" 맥락을 보고 전부 `issue_created`로 라벨했다.

| 이슈 | 실제 생성일 | 실제 완료일 | evidence 타임스탬프 | 모델 라벨 |
|---|---|---|---|---|
| HT-7 | 03/20 | 03/24 | 2026-03-24 (closedAt) | `issue_created` ← 오류 |
| HT-22 | 04/11 | 04/29 | 2026-04-29 (closedAt) | `issue_created` ← 오류 |
| HT-29 | — | — | 2026-05-09 | `issue_created` ← 불명확 |

결과적으로 summary의 **"3월 24일에 이슈가 생성되었고"는 사실과 반대** — 03/24는 완료일이고 생성일은 03/20이다. 사용자에게 잘못된 타임라인을 전달한 가장 심각한 오류.

**근본 원인:** `get_issue_context`가 `createdAt`/`closedAt`을 분리하지 않고 `occurredAt`만 반환. 모델이 맥락 추정으로 event_meaning을 채우다 완전히 역전된 라벨을 붙였다. `get_timeline`을 호출했다면 `createdAt`(issue_created)과 `closedAt`(issue_closed)이 분리되어 이 오류가 없었을 것.

---

### 문제 2 — 생성일이 그래프에 없어 타임라인 절반 누락

`get_issue_context`가 `createdAt`을 반환하지 않아 실제 시작 시점(03/20, 04/11)이 아예 누락됐다. "시간순 정리" 질문에서 정작 시작 시점 없이 완료 시점만 나열된 것.

**개선 방안 — `queries.py`:**
```cypher
toString(i.createdAt) AS createdAt,
toString(i.closedAt)  AS closedAt,
-- 현재는 occurredAt만 반환 중
```

---

### 문제 3 — HT-22 summary에 quote를 초과한 내용 (환각)

HT-22 quote: `"Slack 데이터 전처리"` (제목뿐)

summary: "잡담 메시지를 규칙 기반 및 LLM 기반으로 제거하는 필터링 작업이 진행되었습니다" — quote에 없는 구현 세부사항. 이슈 body 내용을 summary에는 쓰면서 quote에는 제목만 넣었거나, project_context/사전지식에서 가져온 것.

---

### 문제 4 — HT-29 quote에 "그래프" 삽입

실제 제목: `"슬랙 데이터로 생성"` → quote: `"슬랙 데이터로 그래프 생성"` — "그래프"가 추가됐다. "요약·번역·재구성 없이 직접 인용" 규칙 위반.

---

### 문제 5 — 세부 방법·기술 스택을 unknown 처리했지만 커밋에서 찾을 수 있었음

unknown_aspects: "구체적인 데이터 정규화 작업의 세부 방법이나 사용된 기술 스택에 대한 정보는 확인되지 않음."

HT-7·HT-22·HT-29의 `issue_key`를 이미 알고 있었으므로 `get_issue_context` 또는 `get_timeline`을 호출하면 연결된 커밋의 메시지와 `diffSummary`에서 구현 방법과 기술 스택을 확인할 수 있었다. 탐색 없이 unknown으로 처리한 케이스 4 패턴 반복.

시스템 프롬프트의 "즉시 '확인되지 않음'으로 종료 금지 — 최소 한 번은 도구를 호출해 탐색하세요" 규칙 위반.

---

### 문제 6 — get_timeline 미호출로 커밋·PR·Slack 논의 누락 (케이스 2·8 반복)

"시간 순으로 정리"라는 명시적 요청에도 `get_timeline`을 호출하지 않아 커밋·PR 머지·Slack 논의가 타임라인에서 빠졌다. 이슈 제목 3개만 나열한 것은 질문 의도에 크게 미치지 못한다.

---

## 케이스 10 — "그래프 구축·재구축이 전역으로 발생하던 문제는 누가 발견했고 어떤 슬랙 메시지에서 논의되었으며 어떤 커밋 또는 PR에서 수정된 거야?"

확인일 2026-08-19 (프로젝트 `eb74cbd9-33ce-4c0e-9272-d30a661830f3`). summary 문단 서식 변경을
검증하다 발견했으며, **서식과는 무관한 검색·근거 선택 문제다.**

이슈·커밋·PR 축(HT-95 / `f570da7` / PR #47)은 정확히 찾았다. 슬랙 축만 틀렸다.

### 문제 1 — 주제가 무관한 스레드를 근거로 인용하고 의미까지 오독

답변이 인용한 스레드 `1782451627.362269`(2026-06-26)의 전문은 다음과 같다.

| 시각 | 작성자 | 본문 |
|---|---|---|
| 05:27 | 정세영 | "Rise에서 부재중 와있는데 아마 계정 2차인증 때문일수도 있을거 같아서…" |
| 05:39 | 정세영 | "근데 이게 최대 100달러까지밖에 충전이 안된다고 그래서" |
| 05:44 | 서준수 | "뭔가 기준이 openai 내부적으로 있다는데 뭔지 잘 모르겠네" ← 답변이 인용한 문장 |
| 05:49 | 정세영 | "견적서 낸거는 방금 충전한 금액으로 맞춰서 rise쪽에서 다시 수정해주겠다고…" |

**결제·충전 한도 대화다.** 여기서의 "openai 기준"은 계정 충전 상한이지 API rate limit이 아니다.
답변은 이를 "OpenAI 한도 관련 논의가 있었음을 확인할 수 있다"로 서술해, 표면 키워드("openai",
"한도")만 겹치는 대화를 질문 주제의 근거로 승격시켰다. 시스템 프롬프트가 금지하는 *근거 이상의
확신*에 해당한다.

### 문제 2 — 정답 스레드가 같은 프로젝트에 있는데 놓침 (케이스 3·9 패턴 반복)

질문이 묻는 내용이 그대로 적힌 스레드 `1782286987.165039`(2026-06-24)가 **같은 프로젝트에 존재한다.**

| 시각 | 작성자 | 본문 |
|---|---|---|
| 07:43 | 정세영 | "backend에서 ai-engine 호출 타임아웃 설정 작업하고있는데, 스레드 풀 관리때문에 그래프 구축하는 것을 비동기로 전환이 필요해서 알아보다가 **현재 그래프 구축, 재구축이 전역으로 관리되고**…" |
| 08:48 | 서준수 | "openai api 호출에 rate limit 때문에 동시에 처리하는게 좀 걸리는데…" |

첫 메시지가 **"누가 발견했는지"(정세영)와 "어떤 슬랙 메시지에서 논의됐는지"를 동시에 답한다.**
그런데 답변은 "누가 처음 발견했는지는 단정할 수 없다"고 쓰고 unknown_aspects에도 같은 취지를
넣었다 — 그래프에 있는 근거를 unknown으로 처리한 케이스 3·9와 같은 패턴이며, 이번에는 그
원인이 판단 실패가 아니라 **애초에 해당 스레드를 검색으로 가져오지 못한 것**이다.

**회귀다.** 2026-07-23 eval 실행(`eval/results/20260723T073848Z`, case-30)에서는 같은 질문에
이 스레드를 정확히 찾아 "정세영이 문제를 발견해 논의했고"까지 답했다. 그 사이 달라진 것은
그래프에 약 2개월치 데이터가 더 쌓인 것이다(검색 랭킹 변화 가설).

### 개선 방안

1. **원인 규명 우선** — 이 질문에서 `search_by_keyword`·벡터 검색이 실제로 무엇을 반환하는지
   찍어 본다. 정답 스레드가 후보에 들어오는데 LLM이 버리는 것인지, 후보 자체에 못 드는 것인지에
   따라 대응이 갈린다(프롬프트 vs 검색·랭킹).
2. **인용 전 주제 일치 확인 규칙** — 프롬프트 보강 후보:
   > "슬랙 스레드를 근거로 인용하기 전에, 그 스레드가 질문의 주제를 실제로 다루는지 본문으로
   > 확인한다. 키워드가 표면적으로 겹칠 뿐(예: 결제 맥락의 '한도' vs rate limit) 주제가 다르면
   > 인용하지 말고 unknown_aspects에 남긴다."

---

## 공통 근본 원인

| 계층 | 문제 |
|---|---|
| 모델 | gpt-4o-mini가 프롬프트 규칙을 일관되게 따르지 못함 (summary↔evidence 매핑, descendants 순회) |
| 프롬프트 | 규칙이 있지만 특수 케이스(Issue author 매핑, descendants 처리, 노드 중복, "왜" 질문 처리) 명시 부족 |
| 도구 응답 | `creator` vs `author` 필드명 불일치, `closedAt` 미반환으로 LLM의 추정 여지를 만듦 |

### "왜/어떻게" 질문 실패 패턴 (케이스 2, 4, 8에서 반복)

그래프에 명시적 도입 이유가 없을 때 모델이 합리적으로 들리는 이유를 지어내는 경향이 있다.

| 케이스 | 처리 방식 |
|---|---|
| HT-3 | project_context의 프로젝트 설명을 HT-3 도입 이유로 끼워넣음 |
| HT-35 | 구현 detail(IF NOT EXISTS)을 이유로 재포장 + 근거 없는 "성능 향상" 추가 |
| 파이프라인 초기 구조 | "어떻게 정해졌어"(과정)를 "무엇이 정해졌어"(결론)로 대체, 탐색 없이 unknown_aspects 처리 |

### event_meaning 신뢰도 문제 (케이스 2, 8에서 반복)

HT-3의 동일 타임스탬프(`2026-05-04T01:25:55.805Z`)에 케이스 2에서는 `issue_closed`, 케이스 8에서는 `issue_created`가 붙었다. 도구 응답에 `createdAt`/`closedAt`이 분리되지 않으면 모델이 컨텍스트에 따라 event_meaning을 추정하므로 일관성이 없다. `get_timeline` 호출 시에만 두 타임스탬프가 명확히 분리된다.

---

## 케이스 11 — 슬랙 논의 요약 질문에서 summary가 원문을 따옴표째 옮김

### 문제

summary가 그래프에 저장된 Slack 원문을 따옴표째 그대로 옮겼다. 실제 응답:

> "그럼 나중에 추상화 하는걸로 할까?", "추상화 할때 전체소스에 대한 추상화하고 그 안에서 노드에 따른 추상화를 해야하려나", "우선 한명이 추상화를 해서 pr올리는게 나을거같아"라고 이어서 말했다.

읽는 사람이 Slack 원문을 그대로 다시 읽게 되고 "그래서 무슨 얘기였나"는 스스로 해석해야 한다 — summary가 요약이 아니라 발췌 나열이 된 것.

**원인:** 시스템 프롬프트에 `summary` 서술 규율이 없었다(분량·문단 규칙만 존재). 반대로 직접 인용을 부추기는 문구가 셋 있었다.

- `[답변 규칙]` — "summary, unknown_aspects, evidence[*].quote 모두 한국어로 작성하세요 (단, 원문이 영어/코드면 그대로 인용)."라는 한 문장에 summary와 evidence[*].quote가 묶여, "그대로 인용" 예외가 summary까지 번져 읽혔다.
- `[Slack/Communication 인용 규칙]` — 절 제목과 "메시지를 인용할 때 가능하면 conversation_id를 함께 표기해…" 본문 모두 인용 대상이 evidence인지 summary인지 명시하지 않았다.
- 타임스탬프 의미 사전의 "summary·unknown_aspects 본문에서도 도구 결과의 ISO 문자열을 그대로 옮겨 적으세요" — 시각 표기 형식을 지시한 문장이지만, "본문에서도 그대로 옮겨 적으라"는 문구가 시각 주변 서술까지 원문 복사를 허용하는 것으로 오독될 여지가 있었다.

모델이 규칙을 어긴 게 아니라 규칙이 없어 가장 안전한 행동(원문 복사)을 고른 것이다.

### 개선 방안

1. `orchestrator.py` `_SYSTEM_PROMPT`에 `[summary 서술 규칙 — 원문은 옮기지 말고 풀어 쓴다]` 절을 신설했다. summary는 간접 인용으로 쓰되, 식별자(파일 경로·커밋 해시·이슈 키·PR 번호·함수명·설정 키)와 ISO 시각, 이슈·PR 제목은 원문을 유지한다.
2. 위 세 문구를 evidence 대상으로 한정했다. `[답변 규칙]`은 "summary·unknown_aspects는 한국어로 서술하고, evidence[*].quote는 원문 언어 그대로 인용"으로 분리했고, `[Slack/Communication 인용 규칙]`은 절 제목을 `[Slack/Communication 근거(evidence) 인용 규칙]`로 바꾸고 본문도 "evidence로 메시지를 인용할 때…"로 좁혔다. 타임스탬프 사전의 문장 자체는 유지했다(ISO 시각은 summary에도 그대로 남아야 하는 정당한 예외이므로) — 대신 새 [summary 서술 규칙] 절에서 "원문 그대로 옮기는 게 맞는 대상은 식별자와 시각뿐"이라고 명시해, 두 규칙이 충돌 없이 양립하도록 정리했다.
3. `_GROUNDED_ANSWER_SCHEMA`의 `summary.description`에 같은 취지 한 문장을 추가했다.
4. `_count_direct_quotes()`를 신설했다 — **관측 전용** 검출기다. summary의 따옴표 조각이 이번 턴 도구 결과에 그대로 있으면 위반으로 세어 `logger.warning` + `debug["direct_quotes"]`에 기록한다. **답변(structured)은 절대 변형하지 않는다** — summary 문장을 강제로 고치면 오히려 부자연스러운 결과를 만들 수 있어, 관측 후 프롬프트를 개선하는 간접적인 방식을 택했다. 예외: 토큰 3개 미만의 짧은 인용, 식별자 바로 뒤에 붙은 제목 표기(`HT-26 '제목'`, `HT-26의 '제목'`, `PR #20 "제목"`), 영어 축약형 아포스트로피(`don't`, `it's`).
5. `eval/grader.py`가 그 기록을 run별 `direct_quote_spans`로 집계하고, `aggregate()`가 `runs_with_direct_quotes` 카운터를 낸다. `eval/compare.py`의 `COUNTER_KEYS`에 등록했다.

**역할 분리가 핵심이다.** `evidence[*].quote`는 직접 인용을 그대로 유지해야 한다 — 서버가 `_drop_unverified_quotes`로 이번 턴 도구 결과와 대조해 불일치하는 근거를 버리므로, "풀어 써라"가 quote까지 번지면 재서술된 문장이 원문과 어긋나 근거 카드 전체가 삭제된다. "원문을 옮기지 마라"는 summary에만 적용되고, 원문을 그대로 보여주는 일은 evidence[*].quote가 전담한다.

**알려진 한계:** 검출기는 **따옴표로 감싼** 복사만 잡는다. 따옴표 없이 원문을 그대로 옮기는 경우는 못 잡는다. 또한 답변을 고치지 않고 세기만 하므로, 프롬프트가 안 먹히면 지표만 올라간다. 효과는 타깃 스모크로 검증 예정.

---

## 케이스 12 — 답변에서 PR 인용이 통째로 사라짐 (evidence 후검증이 유효 근거를 삭제)

### 문제

2026-08-13 `af0430d`("evidence id 후검증 — 오타 식별자 근거 제거") 이후 **모든 답변에서
`pull_request` 근거가 100% 삭제**되고 있었다. 케이스 11 측정 중 `debug.dropped_evidence`를
확인하다 발견했다 — 삭제된 근거 15건이 **전부 PR**이었다.

```
7/23 런 (id 검증 도입 前) — 44런 | 살아남은 PR 근거 47건 | 삭제  0건
8/19 런 (id 검증 도입 後) — 15런 | 살아남은 PR 근거  0건 | 삭제 15건
```

원인은 **표기 불일치** 세 단이다.

1. 시스템 프롬프트가 PR 근거 id를 `"#번호"`(예: `#18`) 형식으로 강제한다.
2. 도구 결과에는 PR이 `"pr_number": 18`로만 실린다 — `#`가 어디에도 없다
   (이번 런의 전 도구 결과를 전수 조사한 결과 PR 표기는 이 한 가지뿐이었다).
3. id 후검증이 "id가 도구 결과 haystack에 부분 문자열로 존재하는가"를 요구한다 →
   `#18`은 절대 매치되지 않는다 → 전량 삭제.

두 번째 결함도 같은 검증 경로에 있었다. `_canon`이 `\n`만 공백으로 접고 **`\r`을 처리하지
않아**, CRLF로 저장된 본문(GitHub PR·이슈 본문에 흔하다)에서 여러 줄을 인용하면 haystack에
남은 `\r` 리터럴 때문에 항상 불일치했다.

**이 결함은 e2e 측정 자체를 오염시킨다.** PR 근거가 사라지면 ① 골든셋의 PR id를 맞히지 못해
recall이 떨어지고 ② summary는 PR 사실을 서술하는데 뒷받침 근거가 없어 judge가 환각으로
판정한다. 실제 judge 사유가 그대로였다 — *"PR #1의 생성 및 머지 사실은 evidence에 있으나
evidence_originals에는 PR 관련 원문이 없어 직접 확인 불가"*.

### 개선 방안

1. `_id_verified(evidence_type, eid, haystack)` 신설 — `pull_request`는 `#`를 뗀 번호를
   `pr_number` 키 뒤의 숫자와 대조한다(`pr_number"?\s*:\s*<N>(?!\d)`). **`#`만 떼고 부분
   문자열 검사를 하면 안 된다** — haystack의 아무 숫자에나 걸려 `af0430d`가 지키려던 오타
   가드(실기 사례: 해시 `8cdb0ca` ↔ 실제 `8cdb0cc`)가 무의미해진다. `(?!\d)`로 `#1`이
   `"pr_number": 18`에 오매칭되는 것도 막는다. 다른 타입은 기존 부분 문자열 검사 유지.
2. `_canon`이 `\r`도 공백으로 접는다. haystack·quote 양쪽에 대칭 적용되므로 기존 동작 불변.

### 결과

프롬프트를 고정한 짝지은 비교(`results/20260819T134235Z` → `20260819T144633Z`)에서
**recall +0.060 · precision +0.052 · 환각률 −0.115 · 사실 정답률 +0.090 — 네 지표 전부
노이즈 플로어 초과 개선.** 실런의 살아남은 PR 근거 0건 → 20건, 오염 케이스 1 → 0.

저장 응답 replay로 삭제 15건 중 **14건 복구**를 확인했다. 남은 1건은 모델이 PR 본문 중간을
말줄임 없이 건너뛰고 이어 붙인 **조합 인용**이라 정상 삭제다(스키마는 `…`로 생략하도록 허용한다).

### 교훈

- **evidence id 표기 규약은 프롬프트와 도구 결과 양쪽에서 같아야 한다.** 한쪽만 바꾸면
  후검증이 조용히 근거를 지운다. 새 노드 타입을 추가할 때 이 대조를 체크리스트에 넣는다.
- 후검증이 무언가를 지울 때는 **어떤 타입이 얼마나** 지워지는지 봐야 한다. 이 결함은
  `debug.dropped_evidence`에 계속 기록되고 있었지만 아무도 집계하지 않아 6일간 드러나지 않았다.

---

## 케이스 13 — 답변에 시스템 내부 필드명이 그대로 노출 ("discussion_count가 12건입니다")

### 문제 1 — 필드명이 사용자에게 보이는 문장에 실림

"가장 논의가 많이 된 주제가 뭐야?"에 대한 실제 응답:

> 가장 논의가 많이 된 이슈는 HT-129 "integration 추상화 작업"으로, **discussion_count가 12건**입니다.

사용자는 이 시스템의 내부 구조를 모르므로 `discussion_count`는 뜻 없는 문자열이고, 도구 결과의
스키마를 그대로 드러내는 노출이기도 하다.

**원인:** 모델이 규칙을 어긴 게 아니라 **그 지표를 부를 다른 이름이 없었다.**
`rank_issues`는 지표를 `discussion_count`라는 키로만 주고(`tools/queries/issue.py`), 도구 설명도
"각 결과에 discussion_count와 duration_days가 함께 실린다"로 필드명만 알려 줬다. 게다가
`[summary 서술 규칙]`의 "식별자·설정 키 이름은 원문 그대로"가 필드명까지 덮는 것으로 읽힐 여지가
있었다. 케이스 11과 같은 구조다 — 규칙이 없어 가장 안전한 행동(그대로 옮기기)을 고른 것.

### 문제 2 — 그 용어를 되묻자 추측으로 답함

이어진 "discussion_count가 뭔데?"에 대한 응답:

> …이 숫자가 정확히 어떤 이벤트를 세는지(예: 댓글, 슬랙 연계 메시지, 특정 관계의 개수)는
> 이번 도구 결과만으로는 확인되지 않습니다.

지표의 정의가 시스템 어디에도 문장으로 없었다. `[답변 규칙]`이 "도구 결과에 없는 내용은 추측
금지"이므로, 모델에게는 얼버무리는 것이 유일한 선택지였다. **정의를 주지 않은 채 근거만
요구하면 이렇게 된다.**

### 개선 방안

1. `agent/glossary.py` 신설 — 내부 용어 → 사용자 표현 매핑의 **단일 출처**. 프롬프트에 실리는
   용어집과 서버 가드의 치환표가 같은 파일에서 파생돼 어긋나지 않는다. 3계층:
   `METRIC_TERMS`(표기 + 정의), `FIELD_TERMS`(표기만), `DETECT_ONLY`(노드 라벨·관계 타입·도구
   이름 — **손으로 쓰지 않고 `NODE_LABELS`/`REL_TYPES`/`TOOLS`에서 파생**하므로 스키마가 바뀌면
   자동으로 따라온다).
2. `_SYSTEM_PROMPT`에 `[내부 용어 노출 금지]` 절 + 용어집 주입. "원문 그대로" 예외가
   **사용자 데이터의 식별자에만** 적용됨을 명시해 케이스 11 규칙과의 충돌을 제거했고, 지표의
   뜻을 되물으면 용어집 정의로 답하도록 **evidence 없이 답해도 되는 유일한 예외**를 열었다.
3. `_sanitize_internal_terms()` 신설 — summary·unknown_aspects의 내부 토큰을 사용자 표현으로
   치환한다. `_count_direct_quotes`(관측 전용)와 달리 **답변을 실제로 고친다**: 사용자 눈에
   보이는 노출이라 관측만으로는 해결되지 않기 때문이다. `evidence[*].quote`는 대상이 아니다 —
   거긴 사용자 원문이라 같은 단어가 있으면 그것이 곧 사용자 데이터다.
   - **키 게이트**: 토큰이 이번 턴 도구 결과에 `"token":` 형태로 실려 있을 때만 치환한다.
     사용자 저장소에 우연히 같은 이름의 심볼이 있어 모델이 그걸 인용한 것이라면 우리 필드가
     아니므로 건드리지 않고 관측만 한다.
   - **조사 교정**: 모델은 영어 토큰의 발음에 맞춰 조사를 고르므로(`duration_days를`)
     한국어 표기로 갈아끼우면 받침이 어긋난다(`진행 기간(일)를`). 치환 뒤 조사를 표기에 맞게
     고친다(ㄹ 받침의 `으로`→`로` 포함). 뒤에 한글이 이어지면 조사가 아니라 단어이므로 건드리지 않는다.
   - `DETECT_ONLY`는 치환하지 않고 센다 — 사람이 표기를 정하지 않은 어휘를 기계가 갈아끼우면
     문장이 어색해진다. 이 로그가 "실제로 무엇이 새는지" 알려주는 신호이고, 반복 등장하는
     토큰을 용어집으로 승격시키는 근거가 된다.
4. `eval/grader.py`가 `internal_terms_replaced`(서버가 고친 건수 = 프롬프트 준수 신호)와
   `internal_terms_detected`(고쳐지지 않고 남은 노출 = 0이어야 하는 제품 지표)를 나눠 집계하고,
   `aggregate()`가 `runs_with_internal_term_replacements`/`runs_with_internal_terms_left`를 낸다.
5. 골든셋 `case-45` 신설 — 이 문제를 만든 랭킹 질문 자체가 골든셋에 **하나도 없었다**(44케이스
   중 랭킹 질문 0건). 그래서 eval이 이 결함을 잡을 수 없었다. 질문은 "관련 대화가 가장 많이
   연결된 이슈가 뭐야?"로 두었다 — 원 질문("가장 많이 논의된")의 사람 기준 정답과 도구 출력이
   어긋나 있어(아래 부수 발견), 그대로 두면 이 케이스가 도구 선택·용어 표기가 아니라 미해결
   지표 문제를 재게 된다.

### 실기동 검증에서 추가로 드러난 2건 (2026-08-21)

재빌드 후 같은 질문을 다시 던져 세 케이스 모두 의도대로 동작하는 것을 확인했다("관련 대화
메시지가 11건 연결돼 가장 많습니다", 되묻기에 용어집 정의로 응답, `duration_days` → "진행 기간").
그 과정에서 같은 계열의 문제 2건이 새로 보였다.

**(a) 내부 식별자 '값'이 본문에 노출** — *"HT-133은 슬랙 스레드 **1786776420.322659**에서 …"*.
사용자에게는 `discussion_count`만큼이나 뜻 없는 문자열이다. 가드는 **이름**만 다루고 이건
**값**이라 잡히지 않았다. 원인은 `[Slack/Communication 근거(evidence) 인용 규칙]`의
"conversation_id를 함께 표기하세요"가 evidence용 지시인데 summary 본문으로 번진 것 —
케이스 11에서 정리했던 것과 같은 계열이다.

치환으로 고치지 않았다. 이건 **무엇으로 바꿀지가 아니라 빼야 하는 것**이라(→ "슬랙 스레드에서")
서버가 문장에서 토큰을 삭제하게 되는데, 삭제는 치환보다 문장을 깨뜨릴 위험이 크다. 프롬프트에
금지 규칙(대체 서술 예시 포함)을 넣고, `_INTERNAL_ID_PATTERNS`로 **세기만** 한다(슬랙 ts·내부
UUID). 로그에서 자주 보이면 그때 삭제 규칙을 검토한다 — `DETECT_ONLY`와 같은 방침이다.
커밋 해시는 사용자가 실제로 쓰는 식별자라 대상이 아니며, 하이픈 있는 UUID 형태만 잡아 충돌을
피한다. 경계를 `\b`로 잡으면 한글이 `\w`라 조사가 붙는 문장("…830f3의")을 통째로 놓치므로
명시적 lookaround를 쓴다.

**(b) 정의 뒤에 붙는 구현 사족** — *"관련 대화 메시지 수의 집계가 각 도구에서 어떻게 구현됐는지
내부 계산식까지는 확인되지 않음"*. 사용자가 물은 것은 수치의 의미이지 구현이 아니고, 이런
문장은 답을 못 준 것처럼 보이게 만든다. 프롬프트에서 금지했다(unknown_aspects 포함).

### eval 전수 런에서 드러난 오치환 (2026-08-21)

45케이스 전수 런(`results/20260821T024434Z`)의 치환 기록을 열어 보니 **14건 중 다수가
오치환**이었다. 대표는 case-16 — 질문이 *"PullRequest 노드에 created_at을 추가한 배경은?"*인데,
답변에서 그 이름이 "생성 시각"으로 바뀌어 **질문이 물은 대상이 지워졌다**(9건).

**원인은 자기참조다.** 이 저장소는 지식 그래프 시스템이고 골든셋은 이 저장소를 분석한다 —
`created_at`·`occurredAt`·`conversation_id`는 **우리 도구 결과의 키이면서 동시에 사용자 코드의
필드명**이다. 1차 키 게이트("도구 결과의 키인가")는 둘 다 참이라 통과시킨다.

**정밀 키 게이트로 고쳤다** — 토큰이 도구 결과에 **키로만** 등장할 때만 치환한다. 커밋 메시지·
PR 본문이 그 단어를 말하고 있으면 사용자 어휘로 본다. 실측으로 갈림이 확인됐다:
`case-16 created_at 전체 6/키 1`(본문에 있음 → 치환 안 함), `case-11 diff_summary 6/6`(키로만
→ 치환).

**검증**(같은 6케이스 18런 재실행, `results/20260821T051901Z`): **치환 15 → 0**, case-16은
3런 모두 `created_at`을 보존했다. 남은 미치환 2건(`conversation_id`·`diffSummary`)은 본문에
14회·2회 등장해 사용자 어휘로 판정된 정당한 경우다.

**시간 필드는 치환 목록에 유지하기로 했다**(2026-08-21 결정). 검토 과정에서 두 대안이 있었다.

- *치환 목록에서 제외* — `occurredAt`은 본문에 안 나오는 경우가 많아 키 게이트로 걸러지지 않는다.
  case-16처럼 **답변의 주제가 스키마 자체**일 때는 필드명이 그대로 남아야 말이 되기 때문이다.
- *질문 인지 게이트 추가*(질문에 그 단어가 있으면 치환 금지) — 관측된 14건 중 **새로 막히는 것이
  0건**이라 보류했다. 질문에 등장하는 토큰은 대개 커밋·PR 본문에도 등장해 키 게이트가 이미 잡는다.

유지를 택한 이유는 커버리지다 — 이 저장소를 분석할 때가 아니라면 시간 필드 노출도 막는 편이
낫고, 관측된 오치환의 심한 형태(질문이 물은 이름이 지워지는 것)는 키 게이트가 막는다.
**남는 위험**: 답변이 스키마를 설명하면서 본문에 없는 시간 필드를 언급하면 여전히 치환된다
(case-16의 `occurredAt` 5건). 이건 어느 게이트도 판정하지 못하는 종류라 **치환 카운터로
관측**한다 — `internal_terms_replaced`에 값이 붙으면 내역을 열어 보는 것이 운영 규칙이다.

**지표 해석 주의** — `internal_terms_detected`는 이 저장소에서 **자기참조 오탐이 섞인다.**
전수 런의 검출 상위는 `Neo4j` 12 · `PullRequest` 9 · `Communication` 4 · `ChangeSet` 3으로,
전부 사용자가 만든 것의 이름이다. 이 지표는 0을 목표로 삼지 말고 **급증을 신호로** 읽는다.
(치환하지 않고 세기만 하기로 한 결정이 옳았다는 증거이기도 하다 — 치환했다면 멀쩡한 답변을
망가뜨렸을 것이다.)

**부수 수정** — 도구 결과의 점수 계열 float를 소수 둘째 자리로 자른다(`tools/executor.py`의
`_round_scores`). *"연결 신뢰도가 0.6319704674079566인 유사도 기반 추정 연결"*처럼 원시 float가
답변 본문에 그대로 실렸다. 직렬화 지점 한 곳에서 처리하므로 모든 도구에 적용된다.

### 부수 발견 — `discussion_count`가 실제로 세는 것

정의를 문장으로 쓰려고 쿼리를 따라가 보니 `(Issue)-[:DISCUSSED_IN]->(Communication)`의 개수이며,
연결 경로가 셋이다: 본문에 이슈 키가 적힌 명시적 참조(`text`), 임베딩 유사도 추정(`semantic`),
그리고 **같은 스레드의 다른 메시지로 전파된 것**(`propagated`, `propagate_thread_discussed_in`).

실측(project `eb74cbd9`, 2026-08-20): HT-102의 13건은 씨앗 메시지 2건(유사도 추정)과 그로부터
전파된 11건으로 이뤄지고, 그중 12건이 스레드 하나(`1782650084.848469`)에서 온다. 즉 "13번
논의됐다"가 아니라 "긴 대화 한 번"에 가깝다. 사용자에게 "논의 수"로 이름 붙이는 순간 이 오해가
생기므로, 용어집 정의에 이 성질을 명시했다.

**지표 자체의 타당성은 별건으로 분리했다** — 상위권 전체에서 본문 명시 참조가 0건이고 순위가
스레드 길이를 따라가는 문제는 `docs/query-followups.md` 5번에 근거와 함께 남겼다. 이번 작업은
용어 노출만 다루고 랭킹 쿼리는 건드리지 않는다(한 번에 하나만 바꾼다 — measurement.md 3.4).

### 교훈

- **모델에게 이름을 주지 않으면 내부 이름을 쓴다.** 도구 결과에 새 지표를 추가할 때는 그것을
  사용자에게 뭐라고 부를지도 함께 정한다(`docs/tools.md`의 도구 항목에 표기를 적는다).
- **숨기기 전에 뜻을 확정해야 한다.** 필드명을 가리는 것만으로는 문제 2가 남는다 — 정의가 없으면
  되물었을 때 여전히 추측한다.

---

## 우선순위별 액션 아이템

| 우선순위 | 파일 | 변경 내용 |
|---|---|---|
| 1 | `orchestrator.py` | evidence author 매핑 규칙 명시 (Issue → creator) |
| 1 | `orchestrator.py` | descendants 순회 규칙 추가 |
| 1 | `orchestrator.py` | "이슈 진행/변경 내용" 질문 시 get_timeline 필수 호출 규칙 |
| 2 | `queries.py` | Issue 쿼리에 `creator.name AS author`, `closedAt` 추가 |
| 2 | `orchestrator.py` | summary에 슬랙 언급 시 message evidence 필수 규칙 |
| 3 | `queries.py` | get_issue_context에 `all_changesets`, `all_discussions` 집계 필드 추가 |
| 3 | 모델 교체 | gpt-4o-mini → gpt-4o/4.1 검토 |
| 1 | `orchestrator.py` | "왜/이유/배경" 질문 시 명시적 근거 없으면 unknown_aspects 필수 기입 규칙 |
| 2 | `orchestrator.py` | unknown_aspects 정의 강화 (evidence 등재 정보 재기입 금지) |
| 2 | `orchestrator.py` | 구조 설명 질문 시 summary에서 레이어·흐름 재구성 규칙 추가 |
| 3 | `orchestrator.py` | PR id '#번호' 포맷 강조 |
| 1 | `orchestrator.py` | message evidence id = conversation_id(루트 ts) 규칙 강화, 리플라이 ts 사용 금지 |
| 1 | `orchestrator.py` | 같은 conversation_id 그룹 → evidence 1건 등재 + get_thread_context 호출 규칙 |
| 2 | `orchestrator.py` | 접속형으로 끊긴 quote 임의 완성 금지 규칙 추가 |
| 1 | `orchestrator.py` | 파일 변경 이유 질문 시 get_file_history 우선 호출 규칙 (search_by_keyword 대신) |
| 1 | `queries.py` | get_issue_context·get_pr_context·search_by_keyword 이슈 응답에 createdAt·closedAt 분리 반환 |
| 2 | `orchestrator.py` | occurredAt 불명확 시 'unknown' 금지, issue면 get_issue_context 추가 호출 규칙 |
| 1 | 검색·랭킹 조사 | 질문 문구가 그대로 담긴 슬랙 스레드를 놓치는 회귀 원인 규명 (케이스 10 — 7월 eval에서는 찾던 스레드) |
| 2 | `orchestrator.py` | 스레드 인용 전 주제 일치 확인 규칙 — 표면 키워드 중복만으로 근거 승격 금지 (케이스 10) |
| 1 | `orchestrator.py` | summary 서술 규칙 신설(간접 인용) + 직접 인용을 부추기던 문구 2곳을 evidence 대상으로 한정, 시각 문구는 유지하되 새 규칙에서 예외로 명시 (케이스 11) |
| 2 | `orchestrator.py`, `eval/grader.py`, `eval/compare.py` | `_count_direct_quotes` 관측 검출기 신설 + `direct_quote_spans`/`runs_with_direct_quotes` eval 지표 추가 (케이스 11) |
| 1 | `orchestrator.py` | evidence id 후검증을 타입 인지로 — PR은 `"#번호"`를 도구 결과의 `pr_number`와 대조 (케이스 12, PR 근거 전량 삭제 해소) |
| 1 | `orchestrator.py` | `_canon`이 `\r`도 접도록 — CRLF 본문의 여러 줄 인용이 항상 검증 실패하던 문제 (케이스 12) |
| 1 | `agent/glossary.py`, `orchestrator.py` | 내부 용어 용어집 신설 + `[내부 용어 노출 금지]` 프롬프트 절 + `_sanitize_internal_terms` 치환 가드 (케이스 13) |
| 2 | `tools/definitions.py`, `docs/tools.md` | `rank_issues` 지표를 필드명이 아니라 사용자 표기로 안내 (케이스 13) |
| 2 | `eval/grader.py`, `eval/compare.py`, `eval/golden/case-45.yaml` | 내부 용어 노출 기계 지표 + 랭킹 질문 골든 케이스 (케이스 13) |
