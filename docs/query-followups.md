# 질의 경로 후속 작업 TODO

> 2026-07-13 질의 모델 교체(gpt-4o-mini → gpt-5.4-mini) 및 reasoning_effort 실험에서
> 발견된 후속 작업 2건. 측정 방법·수치의 근거는 `docs/measurement.md`와 `eval/results/` 참고.

---

## 1. reasoning_effort low/high 스윕 — Responses API 마이그레이션 필요

### 배경

gpt-5.4-mini의 남은 품질 노브인 `reasoning_effort`를 low/high로 스윕하려 했으나,
**chat.completions + function tools 조합에서는 API가 400으로 거부**한다:

> "Function tools with reasoning_effort are not supported for gpt-5.4-mini in
> /v1/chat/completions. To use function tools, use /v1/responses or set
> reasoning_effort to 'none'."

즉 현행 아키텍처에서 조절 가능한 값은 `none`뿐이고, low/high는 **Responses API로
마이그레이션해야** 쓸 수 있다. env 노브 자체는 이미 있다
(`QUERY_REASONING_EFFORT` — `agent/orchestrator.py`의 `_model_kwargs()`,
docker-compose forward 포함. 빈 값이면 파라미터를 보내지 않는다).

### 측정된 것 (none vs medium, 3케이스 × 3회)

| 지표 | none (추론 끔) | medium (기본, 현행) |
|---|---|---|
| 사실 정답률 | 0.30 | **0.70** |
| evidence recall | 0.35 | **0.46** |
| 환각률 | 0.074 | **0.022** |
| 질의당 비용 | $0.045 | $0.050 |
| 평균 지연 | 12.8s | 12.8s |

- 결과: `eval/results/20260713T105106Z`(none) vs `20260713T063237Z`(medium, case-01·03·05 부분집합)
- reasoning이 품질에 실질 기여함이 확인됐다(특히 사실 정답률 2.3배). 비용 절감은 10%뿐이라
  `none`은 채택하지 않았고, **기본값(medium) 유지**로 결론.
- 비용 구조상 지배 항은 추론 토큰이 아니라 툴 결과가 쌓이는 프롬프트 토큰이었다
  (툴 호출 수 33 vs 35로 비슷).

### 할 일

- [ ] `openai_client.py`에 Responses API 게이트웨이 추가 (rate limiter 페이싱·Priority 유지)
- [ ] `agent/orchestrator.py` 에이전트 루프를 Responses API로 전환
      (tools 스키마·structured output·멀티턴 히스토리 변환)
- [ ] `_record_usage`/debug 트랜스크립트 계약 유지 — eval 러너(`eval/runner.py`)가
      `debug.usage`·`debug.tool_calls`를 읽는다
- [ ] 전환 자체의 무회귀 확인: 같은 골든셋으로 medium 전후 비교
      (`docs/measurement.md` 3.4 — 측정 장치가 바뀌는 변경이므로 신·구 한 번씩)
- [ ] low/medium/high 스윕 → 품질·비용·지연 트레이드오프 측정, `eval/improvement-log.md` 기록

**우선순위: 낮음.** 현재 병목은 생성이 아니라 검색(recall 0.42~0.46)이다 — 툴 반환 정책
(최근 20건 컷)·Actor 신원 통합이 먼저다. high의 상승폭은 제한적일 것으로 추정.

---

## 2. LLM 호출 실패가 "조용한 빈 답변"으로 위장되는 문제

### 현상

`agent/orchestrator.py`의 `_call_llm`·`_call_llm_structured`가 **모든 예외를 삼키고
None을 반환** → `/query`는 **HTTP 200 + 빈 답변**(`structured=null`)으로 응답한다.
eval 러너도 HTTP 실패만 세므로 **"실패 0건"으로 집계**된다.

### 재현 (2026-07-13 실측)

`QUERY_REASONING_EFFORT=low` 설정 상태에서 9/9 질의가 0.2~0.9초 만에 빈 응답으로
돌아왔다. 실제 원인(OpenAI 400 BadRequest)은 컨테이너 로그에만 남았고, HTTP 응답·러너
집계 어디에도 드러나지 않았다. 로그를 직접 뒤지기 전까지는 "모델이 빈 답을 했다"와
구분할 수 없었다.

### 왜 문제인가

- 운영에서 모델 설정 오류·API 장애·키 만료가 사용자에게 "빈 답"으로 위장된다.
- HTTP 200이라 backend·프론트·모니터링 어느 층에서도 장애로 안 잡힌다.
- eval에서는 측정 무효 런(잘못 잰 것)이 "품질 낮은 런"으로 둔갑할 수 있다 —
  `runs_structured_null` 지표가 있긴 하나 러너 exit code에는 반영 안 된다.

### 할 일

- [x] `agent/orchestrator.py`: 회복 불가 4xx(400/401/403/404/422)는 전파, 일시 오류(429·5xx·연결)는
      기존 degrade 유지 — `_is_unrecoverable()` (2026-07-14)
- [x] `routers/query.py`: 전파된 `APIStatusError`를 502 + 명시적 detail로 반환 (/query, /query/summary).
      reasoning_effort=low 재현 시나리오로 검증 — 이전 200+빈답변 → 현재 502 (2026-07-14)
- [x] `eval/runner.py`: `structured=null`을 실패로 집계하고 exit code 반영 (2026-07-14)
- [ ] (선택) LLM 오류율을 메트릭/헬스에 노출해 운영 중 조기 감지
- [ ] backend 프록시·web-dashboard가 502 응답을 사용자에게 어떻게 보여줄지 확인
      (현재는 backend가 5xx를 그대로 전달한다고 가정 — 프론트 오류 UX 검토는 별도)

---

## 3. 그래프 레벨 전달 사항 (2026-07-14 풀 런 진단 결과 — 그래프 담당자용)

풀 런(`results/20260714T061708Z`)의 금지사실·오염 케이스를 트랜스크립트로 추적한 결과,
아래 두 건은 e2e가 아니라 **그래프 엣지 레벨**에서 고쳐야 한다.

- [ ] **HT-102 → message:1781533811 DISCUSSED_IN 엣지 검토** — case-39 오염의 직접 원인.
      글로벌 검색 이슈(HT-102)에 무관한 6/16 브랜치 수집 논의 스레드가 연결돼 있어,
      `get_issue_context(HT-102)`가 3/3런 이 스레드를 끌어옴. false positive면 precision
      라벨셋(`eval/edge_labels/`)에 `irrelevant`로 추가하고 엣지 제거 검토.
- [ ] **스레드 단위 DISCUSSED_IN의 해상도 한계** (case-33, 2026-07-15 재조사로 축소) —
      당초 "HT-75 연결 누락"으로 진단했으나 재확인 결과 **HT-75 전용 스레드(1781439419)와
      DISCUSSED_IN 엣지(conf 0.698)가 그래프에 존재**한다. case-33 실패는 모델이 그 경로를
      탐색하지 않은 선택 문제로 재분류(엣지 결함 아님). 다만 HT-54 스레드(1779325412) 안의
      트리거 관련 리플라이가 HT-54로만 묶이는 해상도 한계 관찰 자체는 유효 — 우선순위 낮음.
      해당 연결은 recall 골든 쌍에 회귀 가드로 등록했다(임계값을 0.7 이상으로 올리면 끊김).

참고 — 당장 조치 없음으로 분류한 것: case-30(정답이 검색에 있었고 런 변동성),
case-16(모델이 시사적 근거에서 단정 서술 — judge 엄격성 경계, 골든 재검토 후보 → 2026-07-15 경계 명확화로 해소).

---

## 4. 툴 반환 정책(2계층) 측정에서 발굴된 후속 2건 (2026-07-17)

get_file_history·get_actor_activity 2계층 전환(78f79b9, fd1534d)의 액터 케이스 측정
(case-01·18, `results/20260717T053621Z`·`054103Z`)에서 코드 밖 문제 2건이 드러났다.

### 4a. 측정 장치 갭 — 귀속(authorship) 진술이 구조적으로 환각 판정됨

- [ ] **`eval/graph_lookup.py` `fetch_evidence_body`에 작성자 정보 포함 검토.**
      judge는 인용 노드의 그래프 원문만 보고 판정하는데, 커밋·이슈 원문에 AUTHORED/
      ASSIGNED_TO 엣지 정보가 없어 **"정세영이 작성한 커밋 X"라는 정확한 문장이 전부
      unsupported로 채점**된다 (case-01 run-2 judge: "작성자라는 정보가 명시적으로 존재하지
      않는다"). 액터 케이스의 baseline 환각률 0.17~0.22도 상당 부분 같은 원인.
      **측정 장치 변경이므로** measurement.md 3.4 원칙대로 신·구 judge 입력으로 같은 골든셋을
      한 번씩 재채점(브리지)해야 하며, 진행 중인 풀 런 판정과 섞지 않는다.

### 4b. 신원 열거형 질문의 병목은 검색이 아니라 '대표작' 선정

- [ ] **중요도(대표성) 랭킹 축 검토 — recency도 question-relevance도 아닌 제3축.**
      case-01/18("X가 작성한 커밋·PR과 담당 이슈 정리")의 골든 기대는 프로젝트 초·중반
      대표작(PR #4·#9·#21·#34, HT-21/23/25)인데, 이런 질문은 주제어가 없어 관련도가
      평평하고(실측 0.66±0.02) 모델은 최신 항목을 인용한다. baseline recall ~0의 근본 원인.
      후보: 그래프 연결 중심성(TRIGGERED_BY·CONTAINS·DISCUSSED_IN 개수) 기반 승격,
      또는 골든 쪽에서 열거형 기대를 재설계(case-17 인용 단위 논의와 같은 계열 — 골든
      소유자 협의 필요).

- [ ] **case-17 나열형 질문의 인용 단위 변동성** (2026-07-15 발견) — "HT-25에서 어떤 개선들이
      있었어?"에 모델이 커밋 개별 인용(골든 기대)과 PR 컨테이너 인용 사이에서 런마다 흔들린다
      (순정 코드에서 recall 0.125/0.875/0.125). 이전의 안정적 0.92는 executor 구식 문자열
      잘림이 get_timeline을 깨뜨리며 만든 평평한 커밋 목록을 통째 인용한 부산물이었다(잘림
      수정으로 소멸). 프롬프트 나열 규칙(규칙3)으로는 교정 실패·부작용만 확인(improvement-log
      2026-07-15). 후속 선택지: ① 골든 acceptable에 PR 컨테이너를 부분 인정할지 팀 논의
      ② 나열형 질문 전용 골든을 늘려 변동성을 측정 가능하게 만들기. 골든 소유자와 협의 필요.

---

## 5. 논의 랭킹이 스레드 전파에 지배된다 (2026-08-20)

내부 용어 노출 작업(docs/query-quality-issues.md 케이스 13)에서 `discussion_count`의 정의를
문장으로 쓰려다 발견했다. `rank_issues(by="discussion")`의 순위가 **실제 논의량이 아니라 스레드
길이**를 따라간다.

실측 (project `eb74cbd9`, 2026-08-20):

| 이슈 | 메시지 수 | 스레드 수 | 명시 참조 | 유사도 추정 | 스레드 전파 |
|---|---|---|---|---|---|
| HT-102 글로벌 검색 추가 | 13 | 2 | 0 | 2 | 11 |
| HT-94 ai-engine 최적화 | 13 | 2 | 0 | 2 | 11 |
| HT-38 그래프 파이프라인 수정 | 12 | 4 | 0 | 4 | 8 |
| HT-97 페이지네이션 적용 | 12 | 1 | 0 | 1 | 11 |

- `discussion_count`는 `(Issue)-[:DISCUSSED_IN]->(Communication)`의 **메시지 수**다. 스레드 하나가
  통째로 전파되므로(`propagate_thread_discussed_in`) 긴 대화 한 번이 수치를 지배한다.
- 1위 HT-102·HT-94는 씨앗 메시지가 각 2건뿐이고 둘 다 "pr 날렸어" 류 **공지**다. 게다가 두 이슈가
  같은 스레드("그래프면 ai-engine쪽?")를 공유해 13/13 동률이 만들어졌다.
- 서로 다른 대화 4개에 걸쳐 있고 설계 논의("그래프 생성방안 후보들 … 이젠 확정해서")까지 있는
  HT-38이 사람 기준 1위에 가깝다.
- **상위권 전체에서 명시 참조(`text`)가 0건**이다 — 이 프로젝트의 슬랙에는 이슈 키가 본문에
  등장하지 않아, 순위가 전부 유사도 추정 + 전파로 만들어진다.

- [ ] **랭킹 축을 메시지 수에서 스레드 수로 바꿀지 검토.** `count(DISTINCT c.conversation_id)`로
      바꾸면 "몇 번의 대화에서 언급됐나"가 되어 사람 직관에 가깝다. 다만 `rank_issues` 쿼리 변경이라
      순위가 전부 바뀌고, docs/tools.md 4b의 실측 기록과 골든 `case-45`도 함께 고쳐야 한다.
      씨앗(비전파) 수를 보조 지표로 함께 싣는 안도 후보 — "연결 13건(대화 2개, 직접 언급 2건)".
- [ ] **위 변경 시 용어집 정의도 함께 고친다** (`services/ai-engine/agent/glossary.py`의
      `discussion_count`). 정의는 사용자가 되물었을 때 그대로 답하는 문장이라 계산과 어긋나면
      그대로 오답이 된다.

**현재 상태**: 지표는 그대로 두고, 골든 `case-45`의 질문을 "관련 대화가 가장 많이 연결된 이슈가
뭐야?"로 좁혀 지표가 실제로 답할 수 있는 범위만 재도록 했다. 용어집 정의에도 "긴 대화 한 번이
수치를 크게 만들 수 있다 — 논의가 그만큼 여러 차례 있었다는 뜻은 아니다"를 명시했다.
