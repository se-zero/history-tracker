# 정량 측정 가이드 (GraphRAG eval)

> **이 문서의 목적**: 그래프 연결 로직 개선과 응답 품질 개선 작업을, 감(感)이 아니라
> **숫자로 검증**하는 방법을 설명한다. 
>
> **스코프**: 응답 품질과 그래프 연결 품질만 다룬다. 수집 처리량·지연·비용 같은
> 데이터 처리 효율 측정은 여기서 다루지 않는다.

---

## 1. 왜 측정하는가

이 프로젝트는 "이 코드가 왜 이렇게 바뀐 거지?"에 답하기 위해, 흩어진 협업 툴 데이터를
지식 그래프로 잇고(**그래프 연결**) 그 그래프를 근거로 자연어 답을 만든다(**응답 생성**).
두 영역 모두 계속 손보게 되는데, 손볼 때마다 다음 질문이 따라온다.

- 이 변경이 정말 개선인가, 아니면 그냥 다르게 틀린 것인가?
- 새 기능·리팩토링이 기존 품질을 **몰래 떨어뜨리지(회귀)** 않았는가?
- 여러 조치 중 **무엇이 얼마나** 효과가 있었는가?

측정 체계는 이 세 질문에 "변경 → 점수 변화"로 답하기 위해 존재한다.

### 두 개의 층

측정은 두 층으로 나뉜다. 고치는 대상이 다르고, 그래서 재는 방법도 다르다.

| 층 | 무엇을 고칠 때 쓰나 | 재는 것 | 한 바퀴 비용 |
|---|---|---|---|
| **엣지 레벨** | 그래프 구축 로직 (엣지 빌더·임계값·임베딩) | 엣지 precision / recall | 몇 분, LLM 호출 없음 |
| **e2e 레벨** | 응답 품질 (프롬프트·tools·응답 구조·모델) | evidence recall · 환각률 · 사실 정답률 등 | 수십 분, LLM 비용 발생 |

---

## 2. 공통 전제 — 고정된 그래프 스냅샷

**측정에는 항상 같은 그래프를 쓴다.** 

덤프 파일은 `eval/snapshots/`에 고정해 두고, 모든 측정을 그 위에서 실행한다.

```
eval/snapshots/
  graph-YYYY-MM-DD.dump      # 그래프 덤프 — 모든 측정의 기준 그래프
  events-YYYY-MM-DD.jsonl    # 원천 NormalizedEvent — 그래프를 처음부터 재구축할 때의 입력
```

주의할 점 몇 가지:

- 덤프파일을 **로드하면 현재 그래프가 통째로 교체된다.**

  → 기존의 데이터를 보존 하려면 로드 전에 반드시 그래프를 백업한다.

- **스냅샷 파일**(`eval/snapshots/`)은 용량 문제로 git에 올리지 않는다.

- **정답지**(`eval/golden/`, `eval/edge_labels/`)와 **실행 결과**(`eval/results/`)는 버전 관리한다.

- 그래프구축 로직을 바꿨을 때만 **그래프를 재구축**한다

  → 로직을 확정하면 새로운 덤프파일을 공유한다

---

## 3. e2e 레벨 — 응답 품질 개선 측정

### 3.1 무엇을 재나
프롬프트·tools·응답 구조·모델을 바꿀때 응답이 얼마나 좋아졌는지 측정한다.

골든셋 질문을 ai-engine `/query`에 그대로 던지고, 돌아온 응답을 자동 채점한다.

LLM 응답은 실행마다 문장이 달라 정확 일치가 불가능하다. **점수**를 매기고, 변경 전후의 점수 변화를 본다. → LLM은 비결정적이므로 **케이스당 3회 실행해 평균**을 낸다.

측정 지표는 두 종류다.

**기계 채점** (문자열 비교, 비용 0)

| 지표 | 정의 | 의미 |
|---|---|---|
| evidence recall | `expected_evidence_ids` 중 실제 인용된 비율 | 필요한 근거를 찾아왔는가 (검색 품질) |
| evidence precision | 인용된 근거 중 expected+acceptable에 있는 비율 | 엉뚱한 근거를 끌어오지 않았는가 |
| 오염 검사 | `expected_absent.evidence_ids`가 인용되지 않았는가 | 잘못된 엣지가 답에 새는가 |
| id 포맷 위반 | evidence id가 규정 형식을 지키는가 | 프롬프트 규칙 준수 여부 |
| 직접 인용 조각 수 (`direct_quote_spans`) | summary가 도구 결과 원문을 따옴표째 옮긴 조각 수 | 답변이 원문을 복사하지 않고 풀어 설명했는가 (간접 인용 규칙 준수) |
| 내부 용어 치환 수 (`internal_terms_replaced`) | 모델이 쓴 내부 필드명 중 서버가 사용자 표현으로 고친 건수 | 프롬프트 용어집을 얼마나 지키는가 (모델 신호) |
| 내부 용어 잔존 수 (`internal_terms_detected`) | 치환되지 않고 답변에 남은 내부 어휘 건수 | 절대값이 아니라 **급증**을 신호로 읽는다 — 자기참조 오탐이 섞여 구조적으로 0이 되지 않는다(아래 참고) |

id 비교는 **정규화 후** 수행한다 (PR의 `#` 유무 무시, 커밋 해시 prefix 매치, 메시지는 골든셋 `aliases`로 매치). "근거는 맞는데 포맷만 틀린" 응답이 recall 실패로 잡히지 않게 하기 위해서다.
포맷 위반은 별도 지표로 집계한다.

`direct_quote_spans`는 집계 시 `runs_with_direct_quotes`(위반이 1건 이상인 런 수)로 낸다. 검출기는
**따옴표로 감싼** 복사만 잡으므로, 따옴표 없이 원문을 그대로 옮기는 위반은 잡지 못한다는 한계가 있다.

내부 용어 지표도 같은 방식으로 `runs_with_internal_term_replacements` / `runs_with_internal_terms_left`
카운터를 낸다. 두 지표를 나누는 이유는 **고쳐진 것과 남은 것이 서로 다른 신호**이기 때문이다 — 전자가
늘면 프롬프트를 손볼 때이고, 후자는 용어집에 빠진 어휘를 가리킬 수 있다. 목록 기반이라
용어집·`DETECT_ONLY`에 없는 어휘는 잡지 못하는 한계는 그대로다(`agent/glossary.py`).

**잔존 지표를 0으로 읽지 말 것 — 이 저장소는 자기참조다.** 골든셋이 분석하는 대상이 이 시스템
자신이라, `Neo4j`·`PullRequest`·`Communication`·`ChangeSet` 같은 어휘가 **사용자가 만든 것의
이름**으로 답변에 정당하게 등장한다(2026-08-21 전수 런 실측: 검출 상위가 전부 이 부류). 이
지표는 절대값이 아니라 **급증**을 신호로 읽는다. 치환 지표(`replaced`)는 반대로 0에 가까워야
정상이며, 값이 붙으면 프롬프트가 안 먹혔거나 오치환이 일어난 것이니 내역을 열어 본다.

**LLM 채점** (LLM-as-judge, 기계로 못 재는 것)

| 지표 | 정의 |
|---|---|
| 환각률 | summary 문장 중 근거로 뒷받침되지 않는 비율 |
| 사실 정답률 | `expected_facts`가 summary에 담긴 비율 |
| 금지 사실 검사 | `expected_absent.facts`가 summary에 담기지 않았는가 |
| 규칙 통과율 | 케이스별 `rule_checks` 통과 비율 |

judge에는 답변이 인용한 quote가 아니라 **그래프에서 조회한 evidence 원문 전체**를 준다 —
quote만 주면 "quote를 충실히 옮겼는가"만 재게 되어, 잘린 인용을 임의로 완성한 문장을 놓친다.

### 3.2 테스트 케이스 = 골든셋

`eval/golden/case-NN.yaml` — 케이스 하나가 파일 하나다. 정답을 아는 질문 모음이며, 사람이 직접 그래프와 원천 데이터를 뒤져서 기대 근거와 핵심 사실을 정의한다.
러너는 전체 케이스 파일 내용의 해시를 **골든셋 버전**으로 기록한다.

**골든셋 양식**

```yaml
id: case-07
question: "PR #20 '그래프 생성 파이프라인 안정성 개선'은 어떤 문제들을 해결했어?"

# 일반 질문이면 []. 노드 선택 질문이면 /query 요청 형식 그대로: [{type: issue, id: HT-3}]
focus_evidence: []

# 반드시 인용돼야 할 근거 = recall 분모. 형식 "{type}:{id}"
#   commit → hash 앞 7자 / pull_request → #번호 / issue → issue_key / message → conversation_id(스레드 루트 ts)
expected_evidence_ids:
  - "issue:HT-38"
  - "commit:b5ad923"
  - id: "message:1778586053.322069"    # 변형 id가 섞이면 객체 형태로
    aliases: ["message:1778590761.356869"]   # 리플라이 ts 등 — 정규화 매치용 별칭

# 인용해도 감점하지 않는 추가 근거 (precision 계산에만 사용)
acceptable_evidence_ids:
  - "pull_request:#20"

# summary에 담겨야 할 핵심 사실 (LLM-judge가 판정)
expected_facts:
  - "슬랙 메시지가 필터 없이 전량 저장되던 문제를 룰 필터 + LLM 배치 필터로 해결했다 (HT-40)"

# 오염 감지 — 등장하면 감점
expected_absent:
  evidence_ids:                       # 인용되면 안 되는 근거 (잘못된 엣지가 끌어오는 노드)
    - "message:1780206867.542399"     # 무관한 로그인 디버깅 스레드
  facts:                              # summary에 담기면 안 되는 사실 (근거 없는 추론)
    - "PR #20의 개선 방안이 슬랙 논의로 설계·결정됐다 — 실제 슬랙엔 공지뿐"

# 케이스별 규칙 검사 (judge가 판정, 기계적으로 확인 가능한 수준으로만)
rule_checks:
  - "summary가 슬랙·GitHub·Actor·GraphRAG 안정성 개선 중 3개 이상 영역을 언급"
```

### 3.3 코드 파일과 역할

| 파일 | 역할 |
|---|---|
| `eval/validate_golden.py` | 골든셋의 모든 evidence id가 스냅샷 그래프의 실제 노드로 해석되는지 사전 검증 |
| `eval/runner.py` | 골든셋 질문을 `/query`에 케이스당 N회 던지고 응답을 저장 (채점 안 함) |
| `eval/grader.py` | 저장된 응답을 채점 — 기계 채점 + LLM judge → `scores.json` |
| `eval/compare.py` | 두 `scores.json`의 paired 비교 — 집계·케이스별 델타를 노이즈 플로어로 판정, 측정 장치 차이 경고 |
| `eval/graph_lookup.py` | 공용 헬퍼 — Neo4j 접속, project_id 자동 감지, evidence 원문 조회, id 포맷 규칙 |
| `eval/tests/` | 채점기·비교기 순수 로직 단위 테스트 (Neo4j 없이 실행) |

**러너와 채점기를 분리한 이유**: 응답을 파일로 남겨두면 채점 로직을 고쳐도 **재실행 없이
재채점**할 수 있고, 회귀 원인 진단에 트랜스크립트(도구 호출 목록·토큰)를 다시 쓸 수 있다.

`runner.py`는 backend를 거치지 않고 ai-engine `/query`를 직접 호출한다 — ai-engine의 품질만
격리해서 재기 위해서다. **ai-engine이 내부 서비스 토큰을 요구하므로 러너에도 토큰이 필요하다** —
환경변수 `INTERNAL_SERVICE_TOKEN`을 읽고, `--token`으로 덮어쓸 수 있다. 토큰 없이 돌리면
전 케이스가 401로 실패한다(무인증 엔진을 상대로 돌릴 때만 생략 가능). 실행마다 `{날짜, git 커밋(+dirty 여부), 골든셋 버전, 스냅샷 라벨,
그래프 구조 지표, 토큰 비용}`을 `meta.json`에 기록한다. 이 시계열이 개선 이력의 증거가 된다.

답변 모델·노브 설정은 수동 라벨이 아니라 **엔진 실측값**으로 기록한다 — 러너가
`GET /query/config`로 ai-engine 프로세스에 실제 적용된 설정(QUERY_MODEL·reasoning_effort·
TOOLS_MIN_CONFIDENCE·FILE_HISTORY_*)을 조회해 `meta.engine_config`에 남기고, `--model-label`과
어긋나면 경고한다. "라벨과 실제가 다른" 무효 런(예: reasoning_effort 사고)을 기록 단계에서 막는다.

### 3.4 수행 절차

프롬프트·tools·응답 구조·답변 모델을 바꿀 때 도는 사이클. **한 번에 하나만 바꾼다.**

1. **골든셋을 검증한다.** (골든셋을 건드렸거나 스냅샷을 바꿨을 때)
   ```bash
   services/ai-engine/.venv/Scripts/python.exe eval/validate_golden.py
   ```
   → 확인: 미해석 id가 0건. 오타·스냅샷 불일치로 측정 한 판을 버리는 것을 막는 단계다.

2. **개선 조치를 하나 적용한다.**

3. **러너를 돌린다.** 케이스당 3회 실행하며, 응답이 `eval/results/<UTC타임스탬프>/`에 쌓인다.
   ```bash
   services/ai-engine/.venv/Scripts/python.exe eval/runner.py --graph-snapshot graph-2026-07-05.dump
   ```
   → 확인: 케이스별 `evidence=N tools=N tokens=N` 로그, 실패 0건.
   (일부만 빠르게 보려면 `--cases case-03,case-21`)

4. **채점한다.**
   ```bash
   services/ai-engine/.venv/Scripts/python.exe eval/grader.py eval/results/<run-id>
   ```
   → 확인: 콘솔 마지막 줄의 집계 지표와 `<run-id>/scores.json`.
   기계 채점만 보려면 `--skip-judge` (LLM 비용 0).

5. **이전 실행과 비교한다.**
   ```bash
   python eval/compare.py eval/results/<baseline-run-id> eval/results/<candidate-run-id>
   ```
   집계 델타 + **케이스별 짝지은 판정**(노이즈 플로어 초과만 개선/악화)을 출력한다 —
   집계만 보면 5개 좋아지고 5개 나빠진 상쇄를 놓친다. 골든셋 버전·스냅샷·모델·judge가
   다르면 "측정 장치 차이"로 경고한다 (그 경우 델타를 시스템 효과로 읽지 말 것 —
   골든 버전이 다르면 한쪽을 재채점해 자를 맞춘다).

6. **채택 또는 롤백한다.** 델타가 노이즈 플로어(6장)를 넘으면 채택하고,
   무엇을 바꿔서 무엇이 몇 %p 움직였는지 기록한다. 넘지 못하면 되돌리고 다음 조치로 간다.

> **참고 — 응답 스키마를 바꾸는 경우**
>
> evidence의 id 형식이 바뀌면 그걸 읽는 채점기와 골든셋 표기도 같이 고쳐야 한다.
> **측정 장치까지 함께 바뀌는 유일한 경우**라, 전후 점수를 그냥 비교할 수 없다.
>
> - 고치는 건 **id를 읽는 방법**뿐이다. recall·환각률 같은 **지표의 정의는 그대로 둔다.**
> - 전환 시점에 **신·구 코드를 같은 골든셋으로 한 번씩** 돌린다. 점수가 비슷하면 측정 장치가
>   그대로인 것이고, 크게 떨어졌다면 시스템이 아니라 새 채점기가 id를 못 읽고 있다는 뜻이다.

---

## 4. 엣지 레벨 — 그래프 구축 로직 개선 측정

### 4.1 무엇을 재나

**그래프에 그어진 엣지 자체**를 정답지와 대조한다.

시맨틱 엣지 빌더·유사도 임계값·임베딩 모델을 바꿀 때 쓰는 층이다.

대상은 **시맨틱 엣지** — 임베딩 유사도로 추론해서 그은 엣지다.

| 타입 | 방향 | 시맨틱 판별 |
|---|---|---|
| `REFERENCE` | ChangeSet(커밋) → Communication(스레드) | 전부 시맨틱 |
| `DISCUSSED_IN` | Issue → Communication(스레드) | `confidence` 속성이 있는 것만 (텍스트 매칭·스레드 전파는 속성 없음) |
| `TRIGGERED_BY` | ChangeSet(커밋) → Issue | `source='semantic'`인 것만 |

두 지표를 잰다. 둘은 **반대 방향의 실패**를 잡으므로 함께 봐야 한다 — 임계값을 올리면
precision은 오르고 recall은 떨어진다.

- **precision** = 그래프에 있는 엣지 중 실제로 관련 있는 비율 → **잘못 그은 엣지(false positive)** 를 잡는다.
- **recall** = 연결됐어야 하는 쌍 중 실제 엣지가 있는 비율 → **놓친 엣지(false negative)** 를 잡는다.

### 4.2 precision — 라벨셋

`eval/edge_labels/precision-YYYY-MM-DD.yaml`

만드는 법: 스냅샷 그래프에 **실제로 존재하는** 시맨틱 엣지를 타입 × confidence 버킷으로 층화 샘플링하고, 사람이 각 쌍을 읽고 관련 여부를 매긴다.

샘플러가 양쪽 노드의 그래프 원문을 인라인으로 붙여 주므로, 라벨링하는 사람은 DB를 뒤질 필요가 없다.

confidence 버킷별로 나눠 뽑는 이유는, 채점 결과가 버킷별로 나와서
**"임계값을 어디로 올리면 precision이 얼마나 오르나"** 를 바로 읽을 수 있기 때문이다.

```yaml
graph_snapshot: graph-2026-07-05.dump
project_id: eb74cbd9-33ce-4c0e-9272-d30a661830f3
seed: 42
edges:
  - edge_type: REFERENCE
    confidence: 0.3403
    bucket: 0.30-0.40
    src:
      type: commit
      id: f570da7                    # 커밋 해시 앞 7자
      body: {...}                    # 샘플러가 붙인 그래프 원문 (라벨 판단용)
    dst:
      type: message
      id: '1778586053.322069'        # 스레드 루트 conversation_id
      body: {...}
    label: relevant                  # ← 사람이 채우는 칸: relevant | irrelevant | unsure
    note: null                       # (선택) 애매했던 판단 근거 한 줄
```

- `relevant` — 이 커밋/이슈가 이 대화와 실제로 같은 사안을 다룬다
- `irrelevant` — 유사도로 이어졌지만 실제로는 무관하다 (false positive)
- `unsure` — 원문만으로 판단 불가 (precision 분모에서 제외)

**라벨은 엣지 id가 아니라 "노드 쌍 + 타입"으로 저장한다.** 빌더를 고쳐 엣지가 사라지거나
다시 생겨도 같은 쌍을 재평가할 수 있어야 하기 때문이다.

### 4.3 recall — 골든 쌍

`eval/edge_labels/recall-YYYY-MM-DD.yaml`

만드는 법: **그래프를 보지 않고 사람이 원천 데이터(Slack·커밋·이슈)를
읽어서** "이 둘은 반드시 연결됐어야 한다"고 확신하는 쌍을 적는다. 

그래프에서 뽑으면 "이미 연결된 것"만 나와서 놓친 엣지를 영영 발견할 수 없다.

```yaml
graph_snapshot: graph-2026-07-05.dump
pairs:
  - edge_type: REFERENCE
    src: {type: commit, id: f29ca87}            # 커밋 해시 앞 7자
    dst: {type: message, id: '1781600092.933319'}  # 스레드 루트 conversation_id (반드시 quote)
    note: >
      2026-06-16 슬랙에서 "프로젝트를 지우면 neo4j 노드가 고아로 남는다"는 문제를 발견·공유하고
      본인이 작업하겠다고 선언. 다음 날 같은 작성자가 그 문제를 고치는 커밋을 올리고 같은 스레드
      리플라이로 알림. 이 스레드가 이 커밋의 직접적 배경.
    author: junsu
```

### 4.4 코드 파일과 역할

| 파일 | 역할 |
|---|---|
| `eval/sample_edges.py` | 시맨틱 엣지를 층화 샘플링 → 빈 라벨 칸이 있는 precision YAML 생성. `--count-only`로 분포만 볼 수도 있다 |
| `eval/edge_eval.py` | 두 정답지를 현재 그래프에 대조해 precision/recall 계산 → 콘솔 출력 + JSON 저장 |
| `eval/precheck_embedding_model.py` | 임베딩 모델 후보를 **그래프 재구축 없이** 비교 — 정답지 쌍의 원문만 후보 모델로 임베딩해 분리도(AUC)와 동일-recall precision을 낸다. 재임베딩·재스윕에 들어가기 전 모델 선택용 |
| `eval/graph_lookup.py` | 공용 헬퍼 — Neo4j 접속, project_id 자동 감지, 노드 원문 조회 |
| `eval/tests/test_edge_recall.py` | recall 채점 로직 단위 테스트 (Neo4j 없이 실행) |

**`edge_eval.py`가 하는 일**: 라벨된 각 쌍이 *지금* 그래프에 엣지로 존재하는지 질의해서,

- precision: 존재 + `relevant` → 정답 / 존재 + `irrelevant` → false positive.
  `precision = relevant / (relevant + irrelevant)`. `unsure`는 분모 제외.
  라벨했지만 그래프에서 사라진 쌍은 `vanished`로 분리 집계한다(분모 제외).
- recall: 골든 쌍이 그래프에 있으면 hit, 없으면 miss. `recall = hits / (hits + misses)`.
  **miss는 분모에서 빼지 않는다** — "연결됐어야 하는데 없음"이 바로 recall이 재려는 신호다.
  출력의 `miss_list`가 곧 그래프 개선의 타깃 목록이다.

정답지는 고정된 채 매번 바뀐 그래프만 새로 질의하므로, 항상 같은 기준으로 잰 비교가 된다.

### 4.5 수행 절차 — 반복 (그래프 개선 루프)

빌더를 바꿀 때마다 도는 사이클. **한 번에 하나만 바꾼다** — 그래야 어떤 조치가 효과였는지 귀속할 수 있다.

1. **빌더를 하나 변경한다.** (유사도 임계값 조정, 시맨틱 엣지 로직 수정, 임베딩 모델 교체 등.)

2. **그래프를 재구축한다.** 변경 종류에 따라 두 갈래다.

   **(a) 임계값·시맨틱 엣지 빌더만 바꾼 경우** (대부분) — 노드·임베딩은 그대로 두고 엣지만 재계산.
   ai-engine 재기동 후, 아래 **표준 체인**을 순서대로 호출한다. 지우개(clear) 4개로 이전 빌드의
   시맨틱 엣지를 비우고, 임계값을 파라미터로 주입해 다시 긋는다 — 코드 수정·재배포 없이 스윕할 수 있다.
   > **ai-engine의 모든 라우터는 내부 서비스 토큰을 요구한다**(`/health`만 예외).
   > 아래 호출은 전부 `-H "$AUTH"`를 달아야 하며, 빠뜨리면 **401**이 돌아온다.
   > 토큰은 `infra/docker/.env`의 `INTERNAL_SERVICE_TOKEN`과 같은 값이다.

   ```bash
   # $INTERNAL_SERVICE_TOKEN을 셸로 가져온다 — 안 하면 AUTH가 빈 헤더가 돼 전부 401.
   # `source infra/docker/.env`는 쓰지 않는다 — GITHUB_APP_PRIVATE_KEY가 여러 줄짜리 PEM이라
   # bash가 그 줄들을 명령으로 실행하려다 깨진다(docker compose의 env 파서만 멀티라인을 허용한다).
   export INTERNAL_SERVICE_TOKEN=$(grep '^INTERNAL_SERVICE_TOKEN=' infra/docker/.env | cut -d '=' -f2-)
   BASE=http://localhost:8000
   PID=<PROJECT_ID>
   AUTH="X-Internal-Service-Token: $INTERNAL_SERVICE_TOKEN"

   # 0) 최초 1회 — 기존 엣지에 source 표식이 없으면 clear가 시맨틱만 골라 지울 수 없다
   curl -X POST -H "$AUTH" "$BASE/migrations/triggered-by-source"
   curl -X POST -H "$AUTH" "$BASE/migrations/discussed-in-source"

   # 1) 지우고
   curl -X POST -H "$AUTH" "$BASE/migrations/clear-semantic-triggered-by?project_id=$PID"
   curl -X POST -H "$AUTH" "$BASE/migrations/clear-semantic-discussed-in?project_id=$PID"
   # clear-reference는 끝 라벨을 제한하지 않는다 — ChangeSet→Communication뿐 아니라
   # ChangeSet→Document(Notion) REFERENCE도 함께 지운다.
   curl -X POST -H "$AUTH" "$BASE/migrations/clear-reference?project_id=$PID"
   curl -X POST -H "$AUTH" "$BASE/migrations/clear-semantic-described-in?project_id=$PID"

   # 2) 새 파라미터로 다시 긋는다 (임계값은 스윕 대상 — 아래는 현행 채택값)
   curl -X POST -H "$AUTH" "$BASE/reference/backfill"
   curl -X POST -H "$AUTH" "$BASE/issue-links/build" -H 'Content-Type: application/json' \
        -d '{"triggered_by_threshold": 0.34, "discussed_in_threshold": 0.48}'
   curl -X POST -H "$AUTH" "$BASE/reference/build?threshold=0.44"
   curl -X POST -H "$AUTH" "$BASE/reference/propagate-threads"
   # 1)에서 clear-reference·clear-semantic-described-in으로 지운 Document 쪽(REFERENCE·
   # DESCRIBED_IN)은 위 세 호출로 다시 그어지지 않는다 — 별도 빌드 라우트가 필요하다.
   curl -X POST -H "$AUTH" "$BASE/document-links/build"
   ```

   > **비대칭 이력 — 이 체인은 한동안 Document를 복구 불가로 지웠다.** `clear-reference`는
   > 끝 라벨을 좁히지 않고(Document REFERENCE까지 삭제) 넓게 지우도록 설계됐는데, 이 표준
   > 체인의 "다시 긋기" 단계에는 `document-links/build` 호출이 없었다(라우트 자체가 없었다).
   > 그 결과 Notion 데이터가 있는 프로젝트에서 이 체인을 돌리면 ChangeSet→Document REFERENCE가
   > 지워진 채 복구되지 않았고, 유일한 복구 경로가 `POST /graph/build`(전체 재구축)뿐이었다.
   > **일반화한 점검 규칙**: 지우는 함수(`clear-*`)와 다시 긋는 함수(`*/build`)를 짝지을 때,
   > **끝 라벨 집합이 같은지 대조한다.** 지우개가 여러 라벨을 한 번에 지우도록 넓게 설계됐다면
   > (예: `clear-reference`의 의도적 설계), 그 라벨 각각에 대응하는 build 라우트가 표준 체인에
   > 전부 들어 있는지 확인한다 — 하나라도 빠지면 그 라벨의 엣지는 이 체인으로 영구 소실된다.
   >
   > **임베딩을 다시 만들어야 하는 경우에만** 위 체인 앞에 재임베딩 backfill을 넣는다.
   > 임베딩이 저장된 곳은 4곳(`Communication`·`ChangeSet`·`Issue` 노드와 `MODIFIED` 엣지)이고,
   > 라우트도 4개다. **임계값 튜닝에는 불필요하다** — 임베딩은 그대로고 엣지만 다시 그으면 된다.
   > ```bash
   > # 임베딩 모델을 바꿨다면 force=true로 4종 전부 덮어쓴다 (구 모델 벡터는 신 모델과 비교 불가).
   > # force 없이 호출하면 embedding이 비어 있는 것만 채운다(수집 중 누락분 보정).
   > curl -X POST -H "$AUTH" "$BASE/reference/backfill?force=true"
   > curl -X POST -H "$AUTH" "$BASE/migrations/changeset-embeddings?project_id=$PID&force=true"
   > curl -X POST -H "$AUTH" "$BASE/migrations/issue-embeddings?project_id=$PID&force=true"
   > curl -X POST -H "$AUTH" "$BASE/migrations/modified-embeddings?project_id=$PID&force=true"
   > ```
   > 4종 모두 `{"saved": N, "total": M}`을 반환한다. **`saved != total`이면 일부가 임베딩되지 않은
   > 것이니 재실행한다** — 배치 임베딩은 청크가 실패해도 예외 대신 빈 벡터를 채우고 넘어간다.
   > 신·구 모델 벡터의 차원이 같으면(예: 3-small ↔ 3-large@1536) 섞여도 오류 없이 무의미한
   > 유사도가 계산되므로, 이 대조를 건너뛰면 조용히 잘못된 측정을 하게 된다.
   > **`POST /graph/build?verify=true`를 쓰지 않는다.** `verify`는 clear 스위치가 아니라 **빌더 선택**
   > 파라미터다 — `true`면 링커가 수동 정밀 구축(LLM 검수)으로 바뀌어, 튜닝 대상인 자동구축(임베딩)을 측정하지 못한다.
   > 게다가 LLM 판정은 빌드마다 달라져 비결정적이다. 위 체인은 LLM을 타지 않아 결정적이므로
   > 전후 차이가 곧 변경의 효과다.

   **(b) 임베딩 모델·노드 생성 등 상류를 바꾼 경우** — 원천 이벤트 스냅샷
   (`eval/snapshots/events-*.jsonl`)을 `/test/ingest`로 재주입한 뒤 빌드해야 한다.
   임계값 튜닝 단계에서는 필요 없다.

3. **채점한다.**
   ```bash
   services/ai-engine/.venv/Scripts/python.exe eval/edge_eval.py
   ```
   기본값으로 `precision-2026-07-05.yaml` / `recall-2026-07-05.yaml`을 읽는다.
   다른 파일을 쓰려면 `--precision <경로> --recall <경로>`.

4. **baseline과 비교한다.**
   - precision이 올랐나? 특히 **버킷별 수치**를 보면 어느 confidence 구간이 쓰레기인지 보인다.
   - recall이 떨어지지 않았나? `miss_list`에 새 항목이 생겼다면 그 개선은 엣지를 죽인 것이다.
   - `vanished`(라벨했는데 그래프에서 사라진 쌍)가 늘었다면 그 쌍들도 함께 검토한다.
   - **두 지표를 항상 같이 본다.** precision만 보고 임계값을 올리면 recall을 조용히 잃는다.

5. **새 엣지가 생기는 변경이었다면 precision 라벨을 보충한다.**
   > `edge_eval.py`는 **라벨에 있는 쌍만** 채점한다. 임계값을 내리거나 임베딩을 바꿔서
   > **새로 생긴 엣지는 라벨에 없으므로 precision 계산에 아예 들어가지 않는다.**
   > 이걸 놓치면 "쓰레기 엣지를 잔뜩 늘렸는데 precision은 그대로"인 착시가 생긴다.

   보충하는 방법:
   ```bash
   # 새 그래프에서 임시 파일로 샘플을 뽑는다 (기존 정답지를 절대 덮어쓰지 않는다)
   services/ai-engine/.venv/Scripts/python.exe eval/sample_edges.py \
       --per-type 25 --out eval/edge_labels/_tmp-precision.yaml
   ```
   1. 임시 파일에는 기존 라벨셋에 이미 있는 쌍과 새로 생긴 쌍이 섞여 나온다 (seed 고정이라 상당수 겹친다).
   2. 그중 **기존 파일에 없는 새 쌍만** 골라 `label`을 채운다.
   3. 그 쌍들을 기존 `precision-*.yaml`의 `edges:` 밑에 이어 붙인다. **기존 라벨은 절대 고치지 않는다.**
   4. 임시 파일은 지운다. 정답지는 언제나 기존 파일 하나다.

   > 임시 파일로 뽑는 이유는, 기존 파일에 같은 `--out`으로 덮어쓰면 채워둔 `label`이 전부 비워지기 때문이다.

   반대로 **임계값을 올리는 변경은 엣지가 줄기만 하므로 라벨 추가가 필요 없다.**

6. **채택 또는 롤백한다.** 노이즈 플로어(6장)를 넘는 개선이면 유지하고, 아니면 되돌리고
   다음 조치로 간다. 무엇을 바꿔 어느 지표가 몇 %p 움직였는지 기록한다.

7. **개선을 채택하기로 합의했다면 새 그래프를 스냅샷으로 굳힌다.**
   - 새 덤프 파일을 팀원에게 **직접 전달한다** (git관리 X).
   - 라벨 파일의 `graph_snapshot` 필드를 새 덤프 이름으로 갱신하고 커밋한다. (git 관리 O).
   

8. **recall 골든 쌍은 어떻게 늘리나.** 
    - e2e 측정에서 "필요한 근거를 못 찾은" 케이스를 발견했을 때, 그 근거로
   이어졌어야 할 노드 쌍을 골든 쌍에 추가한다. 
    - 원천 데이터를 읽다가 명백한 연결을 발견했을 때, **기존 쌍은 고치지 않고 추가만 한다.**

### 4.6 문서 엣지 — 라벨 없는 측정

문서 대상 엣지(`DESCRIBED_IN`(Issue→Document)·`REFERENCE`(ChangeSet→Document))는 위 4.2~4.4의
엣지 레벨 eval이 **커버하지 않는다.**

- `eval/edge_eval.py`의 `EXISTS_Q`는 `REFERENCE`(→Communication)·`DISCUSSED_IN`·`TRIGGERED_BY`
  3종뿐이다. Document로 끝나는 엣지가 없다.
- `eval/sample_edges.py`의 `EDGE_SPECS`는 `REFERENCE`의 끝 라벨을 `(comm:Communication)`으로
  고정한다 — `(doc:Document)`로 끝나는 REFERENCE는 이 샘플러가 애초에 뽑지 못한다.
- `eval/graph_lookup.py`의 `ID_RE`(`^(issue|pull_request|commit|message):(.+)$`)에 document
  표기법이 없다.
- 골든셋(`eval/golden/`)에도 문서 쌍이 0건이다.

라벨셋·골든셋이 갖춰지기 전까지는, 아래 **라벨 없는 지표 5개(M1~M5)**로 문서 엣지 로직 변경의
회귀만 잡는다. precision(이 엣지가 실제로 맞는 연결인가)은 이 지표들로 잴 수 없다 — 존재
여부·개수만 잰다.

기준선은 문서 상한 가드(`DOCUMENT_ISSUE_REF_LIMIT`, `docs/normalized-event.md`「Document 참조
상한」)와 소급 정리(`clear_bulk_document_issue_links`)를 적용하기 전/후 실측이다
(project_id `15f83a55-fb4c-417c-b20e-657644ec323c`).

| 지표 | 전 | 후 |
|---|---|---|
| M1 — 대량 문서만 붙은 이슈 / 문서 붙은 이슈 전체 | 24 / 83 | 0 / 62 |
| M2 — text DESCRIBED_IN 엣지 합계 / 문서 수 / 최대 | 88 / 10 / 29 | 10 / 5 / 3 |
| M4 — semantic DESCRIBED_IN 총량 | 142 | 142 (무손실) |
| M4 — REFERENCE→Document 총량 | 177 | 177 (무손실) |

M3(보존 대상 합)은 위 가드·정리와 같은 실행에서 나온 값이 아니라, 그 결과가 맞는지 대조하는
용도의 파생 쿼리라 별도 전/후 값이 없다 — M2의 "후" 값과 일치해야 한다. M5(문서당 semantic
REFERENCE 분포)도 이 정리의 대상이 아니라 별도로 발견된 현상(top-5가 사실상 할당량으로
굳어짐 — `docs/notion-integration.md` §2-6 실측)이라 전/후 비교 대상이 아니고, 현재 상태를
보는 관찰 지표로 함께 싣는다.

**M1 — 대량 문서 의존 이슈**

```cypher
MATCH (d:Document {project_id:$pid})<-[r:DESCRIBED_IN {source:'text'}]-(:Issue)
WITH d, count(r) AS refs
WITH collect(CASE WHEN refs > 5 THEN d END) AS bulk
MATCH (i:Issue {project_id:$pid})-[:DESCRIBED_IN]->(d2:Document)
WHERE i.source <> '__stub__'
WITH i, bulk, collect(DISTINCT d2) AS docs
RETURN count(i) AS issues_with_docs,
       count(CASE WHEN all(x IN docs WHERE x IN bulk) THEN 1 END) AS only_bulk;
```

**M2 — 문서당 text DESCRIBED_IN 히스토그램** (합계 / 문서 수 / 최대)

```cypher
MATCH (d:Document {project_id:$pid})<-[r:DESCRIBED_IN {source:'text'}]-(:Issue)
WITH d, count(r) AS refs
RETURN count(d) AS docs_with_text_refs, sum(refs) AS total_text_edges, max(refs) AS max_text_edges_per_doc;
```

**M3 — 보존 대상 합** (상한 이하 문서들의 text 엣지 총합 — 정리 후 남아 있어야 할 총량과 대조)

```cypher
MATCH (d:Document {project_id:$pid})<-[r:DESCRIBED_IN {source:'text'}]-(:Issue)
WITH d, count(r) AS refs
WHERE refs <= $limit
RETURN count(d) AS docs_kept, sum(refs) AS preserved_text_edges;
```

**M4 — semantic 무손실** (가드·소급 정리는 text만 지운다 — semantic 엣지 총량이 그대로인지 확인)

```cypher
MATCH (:Issue {project_id:$pid})-[r:DESCRIBED_IN {source:'semantic'}]->(:Document)
RETURN count(r) AS semantic_described_in;
```

```cypher
MATCH (:ChangeSet {project_id:$pid})-[r:REFERENCE]->(:Document)
RETURN count(r) AS reference_to_document;
```

**M5 — 문서당 semantic REFERENCE 분포**

```cypher
MATCH (d:Document {project_id:$pid})<-[r:REFERENCE {source:'semantic'}]-(:ChangeSet)
WITH d, count(r) AS refs
RETURN refs, count(d) AS doc_count
ORDER BY refs DESC;
```

**미해결**: M1~M4로 "가드·소급 정리가 대량 문서 오염을 없앴는가"와 "관계없는 엣지 타입을
건드리지 않았는가"는 회귀 없이 확인할 수 있다. 하지만 문서 대상 엣지의 **precision**(이
REFERENCE·DESCRIBED_IN이 실제로 맞는 연결인가)을 라벨 없는 지표로는 잴 수 없다 — 제대로
재려면 Notion 데이터가 있는 **새 그래프 스냅샷**과 `eval/edge_labels/`의 문서 쌍 라벨링이
필요하다.

### 4.7 Slack 노이즈 필터 — 프롬프트 변경 측정 (2026-09-05 신설)

Slack 메시지는 그래프에 들어가기 전에 룰 필터(`graph/slack_filter.py`)와 LLM 필터
(`graph/slack_llm_filter.py`)를 거치고, LLM이 "제거"로 판정한 메시지는 **삭제**된다. 프롬프트를
바꾸면 그래프에 남는 메시지 집합이 바뀌므로, 바꾸기 전후를 같은 코퍼스로 비교해야 한다.
2026-04-26의 실측(Accuracy 88.6% 등)은 정답 라벨이 레포 밖에 있어 재현할 수 없었고, 그 대신
아래 하네스와 라벨 파일을 새로 만들었다.

**무엇을 재나** — 스냅샷(`eval/snapshots/events-2026-07-05.jsonl`)의 Slack 메시지 616건에 룰 필터를
적용한 뒤 남는 467건을 **프로덕션과 같은 묶음**(`graph.slack_batch_filter.group_for_filter` —
스레드 단위, 단독 메시지는 채널·날짜별 50개)으로 LLM 필터에 넣어 메시지별 보존/제거 판정을 얻는다.
같은 프롬프트를 3회 돌려 다수결을 판정으로 쓰고, 런 간 판정이 갈린 메시지(`unstable_urls`)를
노이즈로 기록한다(2026-09-05 기준선에서 8건, 1.7%).

**코드와 파일**

| 파일 | 역할 |
|---|---|
| `eval/slack_filter_eval.py` | 하네스. `run`(판정 생성) · `compare`(두 결과의 뒤집힘을 라벨링 yaml로) · `score`(라벨로 채점) · `profile`(스냅샷에서 프로젝트 프로필 생성) |
| `eval/results/slack-filter-<tag>.json` | run 결과 — 런별 판정, 다수결, 불안정 목록, 프롬프트·컨텍스트 해시 |
| `eval/results/slack-filter-profile-*.txt` | 프로젝트 프로필 문장(`--context-file`로 run에 넘긴다) |
| `eval/slack_filter_labels/*.yaml` | 사람 라벨. `label` 칸만 `keep` / `remove` / `unsure`. 판정과 독립적인 정답지라 프롬프트를 바꿔도 유지하고 새 뒤집힘만 추가한다 |

**수행 절차**

```bash
PY=services/ai-engine/.venv/Scripts/python.exe   # OPENAI_API_KEY는 infra/docker/.env에서 그 키만 읽는다
$PY eval/slack_filter_eval.py run --runs 3 --tag <tag>                 # 컨텍스트 없이
$PY eval/slack_filter_eval.py profile --out eval/results/slack-filter-profile-<날짜>.txt
$PY eval/slack_filter_eval.py run --runs 3 --tag <tag> --context-file eval/results/slack-filter-profile-<날짜>.txt
$PY eval/slack_filter_eval.py compare --base eval/results/slack-filter-<a>.json --new eval/results/slack-filter-<b>.json     --out eval/slack_filter_labels/flips-<날짜>.yaml          # 뒤집힌 메시지만 라벨링 시트로
$PY eval/slack_filter_eval.py score --labels eval/slack_filter_labels/flips-<날짜>.yaml     --results eval/results/slack-filter-<a>.json eval/results/slack-filter-<b>.json   # 개선/악화 건수 + 네 지표
```

**판정 기준** — 뒤집힌 메시지 가운데 정답과 맞게 바뀐 것(개선)과 틀리게 바뀐 것(악화)을 센다.
악화가 노이즈 건수 이하이고 개선이 악화보다 많으면 "큰 하락 없음"으로 본다. 삭제는 비가역이므로
Recall(남겨야 할 것을 남긴 비율)을 Specificity보다 우선한다. 뒤집힌 항목만 라벨링하므로
절대 지표(Accuracy 등)는 뒤집힌 부분집합 안의 값이고, 옛 실측 표와 직접 비교하지 않는다.

**주의**
- 이 코퍼스는 우리 팀 채널 하나라 "다른 프로젝트" 메시지가 거의 없다. 프로젝트 컨텍스트가 곁가지를
  얼마나 걸러 주는지는 여기서 크게 드러나지 않는다.
- 라벨은 사람의 기준이고 프롬프트의 기준보다 넓을 수 있다(예: 스레드 안의 얇은 결정 흐름 메시지를
  보존으로 봄). 채점 결과와 프롬프트 기준이 어긋나면 어느 쪽을 고칠지 먼저 정한다.
- 같은 라벨로 변형을 여러 번 돌리면 그 라벨에 과적합된다. 변형 몇 개를 본 뒤에는 결정한다.

---

## 5. 개선 이력을 남긴다

**숫자는 `eval/results/`에 자동으로 쌓인다.** 스크립트가 못 남기는 건 딱 하나,
**"무엇을 바꿔서 그 숫자가 나왔나"** 다. 채택한 조치마다 `eval/improvement-log.md`에 한 줄 남긴다.

| 날짜 | 조치 | 결과 | 델타 | 채택 |
|---|---|---|---|---|
| 2026-07-08 | descendants 평탄화 | `results/20260708T…Z` | 사실 정답률 +11%p | O |
| 2026-07-11 | 시맨틱 엣지 임계 0.35→0.45 | `results/edge-2026-07-11.json` | 엣지 precision +14%p · recall -2%p | O |
| 2026-07-14 | 프롬프트 인용 규칙 강화 | `results/20260714T…Z` | 노이즈 범위 | X |

- **노이즈 플로어(6장)를 넘는 델타만** 효과로 기록한다.
- 결과 디렉터리·파일명을 같이 적는다 — 나중에 그 실행의 응답 원문까지 되짚을 수 있다.
- **롤백한 조치도 남긴다.** "이건 해봤는데 효과 없었다"가 다음 사람의 시간을 아낀다.

이 로그가 있으면 "무엇이 얼마나 효과 있었는지"를 근거를 갖고 말할 수 있고, 품질 단계를
마무리할 때 개선 전후를 확정할 수 있다
(예: 엣지 precision NN% → NN%, evidence recall NN% → NN%, 환각률 NN% → NN%).

`scores.json`에 토큰 비용이 함께 기록되므로, 품질 개선이 비용 증가(예: 재생성 루프 추가) 없이
이뤄졌는지도 확인할 수 있다.

---

## 6. 참고 — 노이즈 플로어: 얼마나 움직여야 "개선"인가

LLM은 비결정적이라 아무것도 안 바꿔도 점수가 출렁인다. 3회 실행 평균으로 상당 부분을 흡수하지만
run-to-run 변동은 남는다. 그래서 **baseline 3-run의 분산 기반 ~2σ를 최소 유의미 델타로 잡고,
이를 넘는 변화만 효과로 인정한다.** (2회 측정의 차이 같은 표본 1개짜리 추정은 쓰지 않는다.)

판정할 때는 집계 평균 델타에 더해 **케이스별 짝지은 비교**(개선/악화 케이스 수)를 함께 본다 —
집계만 보면 5개 좋아지고 5개 나빠진 상쇄를 놓친다.

**집계와 케이스별은 플로어가 다르다.** 집계 델타는 45케이스×3런을 평균한 값이라 표준오차가
√(3C)로 줄지만, 케이스별 델타는 3런 평균 하나의 차라 훨씬 크게 출렁인다. 같은 값을 양쪽에 쓰면
케이스별 판정이 사실상 무의미해진다 — 2026-08-21 실측에서 **효과가 없는 짝**(같은 그래프·골든,
표현만 바꾼 변경)에 대해 recall 오탐이 **60%(25/42건)** 였다. `compare.py`는 두 표를 나눠 쓴다.

| | recall | precision | 환각 | 사실 | 규칙 |
|---|---|---|---|---|---|
| `AGGREGATE_FLOOR` | 0.034 | 0.042 | 0.046 | 0.040 | 0.057 |
| `CASE_FLOOR` | 0.222 | 0.279 | 0.307 | 0.256 | 0.383 |

산출법 — 기준선의 케이스 내부 분산을 pool해 σ를 구하고, 집계는 `2σ√(2/(3C))`, 케이스별은
`2σ√(2/3)`(두 평균 *차이*의 2σ). **기준선을 새로 잡으면 같은 방법으로 두 표를 함께 갱신한다.**
2026-08-21 기준선(`20260821T060820Z`)의 σ 실측: recall 0.136 · precision 0.171 · 환각 0.188 ·
사실 0.157 · 규칙 0.235. 케이스별 σ가 이렇게 큰 것 자체가 이 골든셋의 성질이다 — 케이스 하나의
델타로 결론을 내지 말고 집계와 방향(개선/악화 케이스 수)을 함께 읽으라는 뜻이다.

엣지 레벨은 LLM 호출이 없어 채점 자체는 결정적이다. 다만 **그래프 재구축에는 LLM 단계가 섞여
있어**(Slack 필터, Actor 동일인 판단, 링크 검증) 같은 코드로 재구축해도 그래프가 미묘하게 다를 수
있다. 그래서 재구축할 때마다 구조 지표(노드/엣지 타입별 수)를 함께 기록해 빌드 편차를 가시화한다.
점수 델타가 노이즈 플로어 부근이면 구조 지표로 빌드 편차 여부를 교차 확인한다.
