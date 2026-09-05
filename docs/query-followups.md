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

---

## 6. 프로젝트 컨텍스트를 README → Document 수집으로 재설계 (2026-08-24)

### 배경

`/query`에는 원래 "프로젝트 컨텍스트"가 있었다 — GitHub README를 LLM으로 요약해 시스템 프롬프트
상단에 주입하는 기능이다. **M1a에서 제거했다.** 이유는 둘이다.

1. **운영자 개인 PAT(`GITHUB_TOKEN`)로 GitHub을 불렀다.** 불특정 다수를 받으면 남의 비공개 레포는
   어차피 못 읽고, 읽히는 경우가 오히려 문제다 — 운영자 권한으로 임의 레포를 읽는 통로가 된다.
   ai-engine에는 인증이 없어(라우터에 `Depends`/미들웨어 0건) 도달 가능한 누구든 `owner/repo`를
   지정해 호출시킬 수 있었다.
2. **프로덕션에서 한 번도 실행된 적이 없다.** backend의 `AiEngineQueryRequest`에 `repo` 필드가
   없어서 `req.repo`가 항상 빈 문자열이었다. eval 러너도 보내지 않는다.

즉 지금까지의 eval 수치는 **전부 프로젝트 컨텍스트 없이** 나온 것이다. 되살리는 게 아니라 새로
만드는 것에 가깝다.

### 설계 — pipeline-worker가 README를 `Document`로 발행한다

수집 시점에 pipeline-worker가 `/repos/{owner}/{repo}/readme`를 읽어
`nodeType: "Document"`, `source: "GITHUB"`로 정규화해 발행한다.

**왜 이 방향인가**

- **자격증명 문제가 없다.** pipeline-worker는 이미 그 프로젝트의 installation token을 복호화해
  GitHub을 호출하고 있다(`source/github/GitHubCollector`). 운영자 PAT가 등장할 자리가 없다.
- **ai-engine은 무변경이다.** `docs/normalized-event.md`의 원칙 — "이벤트는 소스가 아니라
  `nodeType`으로 해석된다 … 새 소스가 기존 `nodeType` 중 하나로 정규화되면 ai-engine 무변경".
  README는 `Document`이고, Notion 커넥터(N1~N3)가 `Document`/`DocumentSection` 소비·청킹·섹션
  임베딩·Layer 4 링크를 전부 만들어 뒀다.
- **프롬프트 주입이 아니라 검색 가능한 근거가 된다.** 기존 도구
  `get_document_context`·`search_documents`로 조회되고 출처로 인용된다. 매 질의마다 시스템
  프롬프트에 밀어 넣는 옛 방식은 답변에 그대로 새어 나온 사고가 있었다
  (`docs/query-quality-issues.md` HT-3).
- **덤**: Layer 4가 README ↔ 커밋(`REFERENCE`), README ↔ 이슈(`DESCRIBED_IN`)를 자동으로 잇는다.

**대안과 비교** — backend가 질의 시점에 README를 읽어 `/query` 본문에 실어 보내는 안도 검토했다.
자격증명은 똑같이 맞지만 ai-engine에 소비 코드가 새로 필요하고, 매 질의마다 본문이 커지며,
결과가 프롬프트 주입으로 남는다. ai-engine이 backend를 직접 부르는 안(`ai-engine → backend`)은
**현재 없는 의존 방향**을 새로 만들어야 해서(클라이언트·내부 토큰 전무) 얻는 것 대비 과하다.

### 폴백(아이디어, 2026-08-28) — README가 없거나 짧으면 사용자가 직접 쓴 설명을 쓴다

레포에 README가 없거나 너무 짧아 요약할 내용이 부족한 경우, **사용자가 프로젝트 생성/수정 시
입력하는 설명**을 대신 컨텍스트로 쓰자는 아이디어. 조사해 보니 이걸 위한 자리가 이미 있다 —
`Project.description`(`ProjectService.createProject`/`updateProject`가 받는 파라미터, 프론트
`api/projects.ts`에도 `description?: string`으로 있음)이 그것이다. **다만 지금은 화면 표시용일
뿐이다** — `ProjectResponse`에만 담기고, backend가 ai-engine에 보내는
`AiEngineQueryRequest`(`question`·`project_id`·`history`·`prior_evidence`·`running_summary`·
`focus_evidence`)에는 이 필드가 아예 없다. 즉 지금 사용자가 프로젝트 설명을 적어도 LLM 답변에는
전혀 반영되지 않는다.

**아키텍처상 걸리는 지점**: `description`은 backend/Postgres가 소유하는 값인데, 이 문서가 설계한
"Document" 노드는 **pipeline-worker가 NormalizedEvent로 발행**해야 ai-engine이 무변경으로 소비할
수 있다(`docs/normalized-event.md` 원칙 — ai-engine은 이벤트만 안다, backend를 직접 부르지 않는다).
그래서 이 폴백을 실제로 만들려면 "값을 어디서 만들어 어떻게 발행 경로에 태울지"를 정해야 한다 —
예를 들어 pipeline-worker가 수집 시점에 backend 내부 API로 `description`을 조회해 README 대신(또는
README가 없을 때만) `Document`로 발행하는 방식이, 이 문서가 이미 채택한 "pipeline-worker가
발행자" 원칙과 가장 일관된다. backend가 직접 이벤트를 발행하는 새 경로를 여는 것보다는 이쪽이
자연스럽다.

**정할 것**: "짧다"의 기준(글자 수?), README와 설명이 둘 다 있으면 어느 쪽을 우선할지(또는 둘 다
쓸지), `description`이 나중에 수정되면 재발행을 어떻게 트리거할지. 아직 설계 전 단계이고, 위
README→Document 설계가 먼저 정리된 뒤에 같이 볼 것.

### 정해야 할 것

- [ ] **`external_id` 규칙** — 재수집 멱등성 키다. `"readme"` 고정값과 파일 경로(`README.md`) 중 선택.
      경로를 쓰면 대소문자·확장자 변형(`readme.rst` 등)이 다른 문서로 잡힌다
- [ ] **actor** — README에는 자연스러운 작성자가 없는데 `_handle_document`가 `resolve_actor`를
      **무조건** 호출한다(`graph/event_handler.py:389`). 마지막 수정 커밋의 author를 쓸지,
      빈 actor를 허용하도록 소비 측을 손볼지 정해야 한다
- [ ] **갱신 주기** — 수집 실행마다 재발행하면 MERGE로 덮어써진다. 매번 부를지, 변경 감지 시에만 부를지
- [ ] **고지** — 개인정보처리방침 `#github` 절의 수집 항목에 README 본문을 추가한다
      (`clients/web-dashboard/CLAUDE.md`: "새 커넥터를 배선하면 제2조 소스 블록을 함께 추가한다")
- [ ] **측정** — 켠 뒤 `docs/measurement.md` 기준으로 재측정한다. 기존 수치와 비교하려면
      컨텍스트 없는 기준선이 이미 있으므로 A/B가 가능하다

### 관련 — Slack 필터 프롬프트에 이 프로젝트 정체성이 하드코딩돼 있다

같은 "프로젝트 컨텍스트" 계열의 잔재가 한 곳 더 있다. `graph/slack_llm_filter.py`의 두 프롬프트
(`_THREAD_PROMPT`·`_STANDALONE_PROMPT`)가 `[프로젝트 컨텍스트]` 자리에 이 문장을 박고 있다.

> GitHub, Jira, Slack 데이터를 연동하여 지식 그래프를 만드는 캡스톤 프로젝트입니다.

원래는 `project_context`가 비었을 때의 폴백이었는데, 자동 후처리 경로가 **항상** 빈 값으로 불러서
사실상 이것만 쓰였다. M1a에서 파라미터를 걷어내며 폴백 문구를 그대로 인라인했으므로
**동작은 그대로이고 회귀도 아니다** — 다만 이제 명시적·영구적이다.

멀티테넌트에서는 남의 Slack 메시지를 "우리 캡스톤 프로젝트" 설명으로 판단하게 된다.

- [x] (2026-09-05) 블록을 걷어내고 `build_prompt(is_thread, project_context="")`로 호출자가 넘기게 했다.
      중립 문장으로 바꾸는 안은 쓰지 않았다 — 고객 프로젝트 설명 자리에 우리 제품 설명을 넣는 셈이라 오도한다
- [x] (2026-09-05) 재측정했다. 2026-04-26 라벨은 레포 밖에 있어 재현 불가라 하네스
      (`eval/slack_filter_eval.py`)와 라벨(`eval/slack_filter_labels/`)을 새로 만들었다. 결과·해석은
      `docs/post-mvp-work.md` 「하드코딩 제거」, 방법은 `docs/measurement.md` 4.6
- [x] (2026-09-05) README를 기다리지 않고 **프로젝트 그래프에서 프로필을 자동 도출**해 채웠다
      (`graph/project_profile.py` — PR url의 저장소 이름·이슈/PR 제목·커밋 메시지·디렉터리·문서 제목을
      최신 절반+최초 절반으로 모아 gpt-4o-mini 요약, 프로젝트별 24h 캐시). README → Document가 들어오면
      `document_titles` 재료로 자동 반영된다

**2026-09-05 처리 완료.** 배포 전 결함으로 재분류돼 `docs/post-mvp-work.md`에서 진행했다.
