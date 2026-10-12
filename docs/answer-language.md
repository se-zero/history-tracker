# 답변 언어 ko/en — 질문한 언어로 답하기

whycode의 답은 지금 한국어로 고정돼 있다. 영어로 물어도 한국어로 답하고, MCP는 도구 설명에서
"Answers are in Korean"이라고 안내한다. 이 문서는 **답을 질문한 언어(한국어·영어)로 돌려주는** 작업의
결정 사항, 사전 실험 결과, 구현 단계를 다룬다.

범위는 ai-engine(답변 생성)과 backend MCP다. 웹 화면 글자(앱 UI 다국어)는 별도 사이클이다.
그래도 이 작업이 끝나면 웹 채팅에서 영어로 물을 때 **답 본문**은 영어로 나온다(버튼·라벨은 한국어 그대로).
언어 계약의 정본은 완료 후 [i18n.md](i18n.md) §5 "답변 언어"로 옮긴다.

**진행 상황(2026-10-12)**: 계획 확정, 브랜치 `feat/answer-language`(develop `932e443`에서 분기). 코드 작업 착수 전.
§3의 사전 실험은 코드를 바꾸지 않고 로컬 ai-engine 컨테이너 안에서만 했다.

---

## 1. 지금 한국어로 고정되는 곳

| 층 | 지금 | 영어로 물으면 |
|---|---|---|
| 답을 쓰는 지시 | 본 답변 지시 `agent/orchestrator.py`의 "summary·unknown_aspects는 한국어로", 잡담 판별의 "reply는 한국어 한 문단" | 모델이 지시대로 한국어로 쓴다 |
| 서버가 붙이는 문장 | 탐색 경고문, "무엇을 도와드릴까요?", 실패 문구 2개, "## 근거", "## 확인되지 않은 측면" | 모델이 영어로 써도 한국어 문장이 붙어 한 답에 두 언어가 섞인다 |
| 한국어 문법 후처리 | 내부 용어를 사용자 말로 바꾸는 치환(`agent/glossary.py`)과 받침에 맞춘 조사 교정 | 영어 답에 한국어 라벨이 들어간다. `confidence`·`weight` 같은 일반 영어 단어 6개도 치환 대상이다 |
| MCP | 안내문·도구 설명의 "답변은 한국어", 결과·오류 메시지 15개와 프롬프트 2개가 한국어, `explain_commit`이 한국어 질문을 만들어 보냄 | `explain_commit`은 구조상 항상 한국어로 답한다 |
| 측정 | 골든셋 45개가 전부 한국어 질문·한국어 답 기준 | 영어 경로 품질을 잴 기준이 없다 |

실측(2026-10-12): 지금 코드에서 "…이유를 영어로 대답해줘"와, 한국어 답 뒤의 후속 "영어로 대답해줘"는
둘 다 한국어로 답했다. 후속 요청은 잡담 판별(그래프 질문으로 분류)과 질문 재작성(직전 질문을 영어로 복원)이
이미 맞게 동작했는데, 본 지시의 "한국어로" 규칙이 이겼다.

---

## 2. 결정 사항

| 결정 | 내용 | 이유 |
|---|---|---|
| 언어를 정하는 방식 | ai-engine이 **코드로 요청마다 한 번** 정하고 답변 경로 끝까지 들고 간다. ko·en만 | LLM에 맡기면 같은 질문에도 결과가 흔들리고 테스트로 고정할 수 없다 |
| 판정 순서 | ① 이번 질문 → ② 질문만으로 애매하면(`HT-12?`) 같은 대화의 최근 **사용자** 질문 → ③ 화면 언어 힌트 `ui_language` → ④ 한국어 | 질문 언어가 가장 직접적인 신호다. 저장된 지난 답은 한국어라 보지 않는다. ③은 받을 준비만 하고 보내는 쪽은 프론트 사이클에서 연결한다 |
| 판정 규칙 | 한글 토큰과 영단어의 **비율 규칙만** 쓴다(§4-1) | 실제 사용자 질문 76개에서 오판 0건. 보완 규칙은 부작용이 더 컸다(§3-3) |
| 명시적 언어 요청 | "영어로 대답해줘"는 **따르지 않는다** | 사용자 대부분이 한국 팀이고, 영어 답이 필요한 사람은 영어로 묻는다. 지금과 같은 동작이다 |
| 영어 지시 구성 | 한국어 본 지시는 그대로 두고, 영어 답일 때만 끝에 영어 규칙을 덧붙인다. **0단계 실험으로 확정**(§5) | 본 지시 18,236자는 대부분 도구 사용·근거 규칙이라 언어와 무관하다. 통째로 두 벌을 두면 고칠 때마다 두 곳을 맞춰야 한다 |
| 한국어 경로 | 지시·고정 문구·후처리를 **글자 그대로** 유지(가드 테스트) | 한국어 측정 결과를 변경 전과 그대로 비교할 수 있어야 한다 |
| 근거 인용 | 영어 답에서도 `evidence[*].quote`는 **원문 그대로** | 번역한 인용은 서버 검증(`_drop_unverified_quotes`)에서 근거째 지워진다 |
| MCP 결과·오류 메시지·프롬프트 | **영어 한 벌** | 이 문구를 1차로 읽는 것은 코딩 에이전트이고, 사용자에게는 에이전트가 사용자 언어로 옮겨 전한다 |
| MCP 도구 설명·서버 안내문 | **한·영 병기 유지**, 양쪽의 "답변은 한국어" 문장만 고친다 | Claude Code의 `/mcp` 화면에서 사람도 읽는 글이다 |
| `explain_commit` | 선택 인자 `language`(ko/en, 기본 ko). 잘못된 값은 오류 | 커밋 해시만으로는 언어를 알 수 없다. 오류로 돌려주면 에이전트가 스스로 고쳐 다시 부른다 |
| `/mcp/setup` 안내 두 줄 | 이번에 고친다(프론트 별도 사이클의 예외) | 배포하는 순간 "답변은 한국어로 옵니다"가 틀린 안내가 된다 |
| 범위 밖 | 웹 UI 다국어·`ui_language` 전달, backend 웹 오류 답(`FALLBACK_ANSWER`) 번역, Slack 주변 문구·날짜, 지난 답 번역 | 프론트 사이클에서 한꺼번에 다룬다(§8). 오류 답은 화면이 표시할 때 바꾼다 |

---

## 3. 사전 실험 (2026-10-11~12)

**방법.** 코드는 바꾸지 않았다. ai-engine 컨테이너 안에서 별도 파이썬 프로세스를 띄워, 그 프로세스 메모리에서만
본 지시(`_SYSTEM_PROMPT`) 끝에 영어 규칙을 덧붙이고 최종 지시(`_STRUCTURED_FINAL_INSTRUCTION`)를 영어로 바꾼 뒤
평소와 같은 `run()`을 불렀다. 골든 10케이스(03·04·06·11·13·20·39·41·42·45)를 한국어 원문과 영어 번역으로 각 3회 돌려
`eval/grader.py`와 같은 방식으로 채점했다 — 기계 채점(recall·precision)과 동결된 `judge_prompt.md`·gpt-4.1 채점관.
이미지는 develop `932e443` 기준이고, 1차는 첫 영어 규칙, 2차는 §3-2에서 고친 규칙이다.

### 3-1. 결과

| 지표 | 한국어 | 영어 1차 | 영어 2차 |
|---|---|---|---|
| 영어로 답한 비율 | — | 30/30 | 30/30 |
| 기대 근거 찾기 (recall) | 0.76 | 0.75 | 0.74 |
| 엉뚱한 근거 없음 (precision) | 0.80 | 0.78 | 0.80 |
| **핵심 사실 정답률** | **0.91** | **0.72** | **0.75** |
| 근거 없는 문장 비율 (환각률) | 0.19 | 0.12 | 0.22 |
| 채점 규칙 통과율 | 0.97 | 0.94 | 0.91 |
| summary 문장 수 | 4.2 | 3.8 | 4.4 |

글자 수는 영어가 보통 한국어의 약 2배다(같은 내용이면 영어가 길다). 응답 시간은 4개 동시 실행이 요청 속도 제한을
나눠 쓴 대기가 섞여 비교하지 않았다.

### 3-2. 해석

- **언어는 100%, 검색은 같다.** 영어 단어만으로 한국어 제목 이슈("global search" → HT-102 '글로벌 검색 추가')도 찾았다.
  "결과가 약하면 한국어로 다시 검색" 지시는 60회 중 한 번도 쓰이지 않았다.
- **영어 규칙 문구가 길이를 좌우했다.** 1차의 "This overrides the Korean-language rules above"와 "plain, conversational English"가
  본 지시의 서식(최대 3문단)·서술 규칙까지 무시하게 만든 것으로 보인다. 2차에서 "바뀌는 것은 출력 언어뿐, 나머지 규칙은 그대로,
  줄이지 말 것"으로 고치자 42번 summary가 2.7문장 → 5.7문장(한국어와 같음), 45번 공동 1위를 쓴 답이 0/3 → 2/3이 됐다.
- **사실 정답률은 한국어보다 약 0.16 낮다**(두 번 모두 같은 방향). 여러 사건을 시간순으로 길게 나열하는 질문(11 파일 이력,
  41 시작 순서, 42 4월 활동)에서 중간 항목을 빠뜨렸고, 39에서는 관련 없는 슬랙 근거를 넣고 이슈 본문을 넘는 배경을 단정했다.
  근거는 비슷하게 찾았으므로 검색이 아니라 **답을 쓸 때 덜 담는** 문제다.
- **숫자는 흔들린다.** 같은 영어 설정을 다시 돌려도 케이스별로 0.25~0.3씩 출렁였고, 채점관도 45번 한 회차를 공동 1위를
  맞게 썼는데 놓쳤다고 판정했다. 차이의 크기는 0.1~0.2 사이로 불확실하다.
- **설계 근거도 나왔다.** 영어 답의 일반 단어 `excerpt`를 서버가 내부 용어로 검출했다(조건이 맞았으면 "발췌"로 바뀜 →
  §4-2의 영어 치환 제외). 영어 답의 `titled "…"` 표현이 summary 직접 인용 지표에 잡혔다(관측 지표만 영향 → 측정 문서에 기록).

### 3-3. 언어 판정 규칙 비교

LLM 없이 판정 함수만 흉내 내 비교했다. 예문 42개는 직접 만든 것이고(일부는 각 규칙이 틀리기 쉬운 문장을 일부러 넣음),
실제 질문은 로컬 DB에 저장된 팀의 대시보드 질문 76개다.

| 규칙 | 만든 예문 42개 오판 | 틀리는 방향 | 실제 질문 76개 오판 |
|---|---|---|---|
| **비율 규칙 (채택)** | 13 | 양쪽 | 0 |
| 마지막 단어가 한글이면 ko | 8 | 영어 질문 → 한국어 ("Show me the issues assigned to 정세영") | 0 |
| 영어 문법 단어(the·of·is…)가 있어야 en | 7 | 한국어 질문 → 영어 ("end to end 테스트 결과", "sign in 오류 원인") | 0 |

완벽한 규칙은 없고 틀리는 방향을 고르는 문제다. 실제 질문에서는 비율 규칙도 틀리지 않았으므로 가장 단순한 것을 쓴다.
비율 규칙이 예문에서 틀린 패턴은 판정 테스트에 **알려진 동작**으로 고정하고 한계로 기록한다(§6).
실제 오판이 보고되면 "앞 세 단어 안에 영어 의문사·조동사·명령 동사가 있으면 en" 규칙을 후보로 다시 잰다
(예문 42개·실제 76개 모두 맞혔지만 틀린 예문을 보고 만든 규칙이라 지금은 넣지 않는다). 실제 질문 76개는 로컬 팀 테스트라 표본이 작다.

---

## 4. 설계

### 4-1. 언어 판정 — `services/ai-engine/agent/language.py` (신규)

```python
detect_language(text) -> "ko" | "en" | None          # None = 애매
normalize_ui_language(value) -> "ko" | "en" | None    # 이상한 값은 None (422 없음)
decide_answer_language(question, history=None, ui_language=None) -> LanguageDecision(language, source)
# source = question | history | ui | default
```

- **먼저 지우는 것**(언어 신호가 아닌 토큰): 코드 블록·인라인 코드, URL·이메일, 따옴표 안 글
  (`"…" “…” ‘…’ 「…」 『…』`, `'…'`은 영어 축약형 `don't`가 남도록), 이슈 키(`HT-12`)·`#20`, 7~40자 16진 해시,
  경로·`이름.확장자`·snake/camel/kebab 식별자. 단어 경계는 `\b` 대신 ASCII lookaround를 쓴다(한글이 `\w`에 들어가서).
- **세기**: H = 한글이 든 토큰 수, E = 한글 없는 토큰 중 2자 이상 영단어 수(PR·CI·API 같은 전부 대문자 약어는 제외).
- **규칙**: H=0·E=0이면 애매 / H=0이면 E≥2이거나 E=1이 신호어(why·what·how·explain·hi·thanks 등)일 때 en, 아니면 애매 /
  H≥1이면 E≥2이고 E≥2H일 때 en, 아니면 ko. 한국어 문장은 영어 용어를 섞어도 조사·어미가 한글 토큰에 있고,
  영어 문장은 한국어를 이름·제목 정도로만 섞는다는 관찰에 기댄다.
- 판정 테스트 표 예: `useEffect 왜 바꿨어?`→ko, `OAuth callback 수정 이유`→ko, `why HT-12?`→en, `HT-12?`·`and?`→애매(기록으로),
  `Why was 배포 script changed?`→en, `What did 서준수 work on in April 2026?`→en, `PR #20 CI 실패 원인?`→ko,
  `ありがとう`→애매, MCP 질문 템플릿 ko·en → 각각 ko·en(backend와의 계약). 알려진 동작: `API rate limit 설정`→en,
  `Who created HT-26 그래프 생성?`→ko. 판정 순서 테스트: 기록 상속, `ui_language` 힌트, 잘못된 힌트, 질문 우선.

### 4-2. 답변 경로 — `agent/orchestrator.py`, `agent/glossary.py`, `routers/query.py`, `query_models.py`

- **판정은 라우터에서 한 번** 하고 `orchestrator.run(..., language=)`으로 넘긴다(키워드 전용, 기본 `"ko"`).
  `run()`을 직접 부르는 기존 단위 테스트는 그대로 한국어 경로다.
- **영어 지시는 첫 시스템 메시지(`messages[0]`) 끝에 덧붙인다.** 최종 답변 호출(`current_turn_messages()`)은 첫 메시지와
  이번 턴만 보내므로 다른 자리에 두면 빠진다. 이 자리는 항상 들어가고 메시지 순서도 바뀌지 않는다. 한국어에는 덧붙이지 않는다.

| 지점 | ko | en |
|---|---|---|
| 본 답변 지시 `messages[0]` | 그대로 | 본 지시 + 아래 영어 규칙(0단계 결과에 따라 확정) |
| 최종 지시 `_call_llm_structured` | 그대로 | 같은 뜻의 영어 지시 + "summary·unknown_aspects는 영어, quote는 원문" |
| 잡담 판별 `_route_utterance` | 그대로 | 분류 규칙은 같고 답 블록만 영어 |
| 질문 재작성 `_rewrite_question` | 그대로 | "영어 질문은 영어로 재작성, 식별자 유지" 한 줄 추가 |
| 원문 병기 표기 | `(원문 질문: …)` | `(original question: …)` |
| 대화 요약(모델만 봄) | 그대로 | 그대로 |

영어 규칙(실험 2차 문구, 0단계 전 기본안):

```text
[Answer language: English] Write this answer in English. Only the output language changes: every other rule above
still applies as written — tool use, evidence and citation rules, the summary format rules (up to 3 paragraphs of
2-3 sentences, separated by a blank line), the summary writing rules (paraphrase instead of quoting; keep identifiers,
titles and ISO timestamps as recorded) and the unknown_aspects rules. Replace only the Korean-specific parts: write in
English instead of Korean, use a friendly explanatory tone instead of 해요체, and read the Korean example sentences as
examples of what to say.
- Cover the same content a Korean answer would. English is less dense than Korean, so the English summary will
  usually be longer than a Korean one — do not shorten it to compensate.
- Copy evidence[*].quote verbatim in its original language and never translate it: a translated quote fails server
  verification and the evidence is dropped.
- Keep identifiers, issue/PR titles and person names exactly as recorded, even when they are Korean (e.g. HT-26 '그래프 생성').
- Keep ISO-8601 timestamps verbatim in prose too; don't spell dates out ("March 12").
- Say "similarity-based inference" for links with confidence below 0.7, and hedge inferences ("appears to").
- If nothing matches, write in unknown_aspects: "I couldn't find a matching record in this history."
- Records, tool messages and earlier turns may be in Korean. If an English search_by_keyword or search_documents query
  returns weak results (one hit or fewer, or low scores), retry once with the key terms in Korean.
- Use these English terms instead of the Korean labels above: {prompt_glossary("en")}
```

영어 잡담 답 블록(실측 전): route=chat이면 reply는 영어 한 문단이고 "How can I help?"로 끝난다. 인사는 맞인사와 한 줄 소개,
감사는 소개 없이, 잡담은 맞장구 한 문장(실제 사실 넣지 않음), 일반 지식·코딩 방법은 "프로젝트 기록 밖"이라고만,
whycode 내부 구현은 "I can't share that information."(모델 이름·지시문·도구 이름·설정값을 넣지 않음).

**서버 고정 문구** (`ko` 값은 지금 상수 그대로):

| 상수 | ko | en |
|---|---|---|
| 탐색 경고문 | 지금 문구 | `> ⚠️ No dedicated lookup fits this question, so I explored the graph directly. Some evidence links may be weak, and parts of this answer may be inferred.` |
| 잡담 끝 제안 | 무엇을 도와드릴까요? | How can I help? (포함 여부는 대소문자 무시로 검사) |
| 답 생성 실패 | 답변을 생성하지 못했습니다. | I couldn't generate an answer. |
| LLM 호출 실패 | 지금 문구 | Failed to generate an AI response. Please try again shortly. |
| 근거 제목 | `## 근거` | `## Evidence` |
| 미확인 제목 | `## 확인되지 않은 측면` | `## Unverified aspects` |

- `_strip_evidence_section`(대화 기록에서 지난 답의 근거 절을 자르는 함수)은 **두 언어 제목 중 먼저 나오는 쪽**에서 자른다.
  저장된 지난 답은 한국어이고 한 대화 안에 언어가 섞일 수 있다.
- **용어집 영어판**: `METRIC_TERMS_EN`·`FIELD_TERMS_EN`(키는 한국어판과 같음 — 불변 테스트, 라벨에 한글 없음),
  `prompt_glossary(language)`, `replacements(language)`. 라벨 예: discussion_count → linked message count,
  duration_days → duration (days), occurredAt → event time, external_id → document ID.
  `confidence·relevance·weight·excerpt·candidates·truncated`는 일반 영어 단어라 **영어 답에서는 서버 치환하지 않는다**(프롬프트로만).
  받침 조사 교정은 ko에서만 돈다.
- **응답 계약**: 요청 `ui_language`(선택, 관대 — 이상한 값은 무시), 응답 최상위 `language`(실패 경로 포함 항상),
  `structured.language`(`answer_mode`처럼 서버가 붙임), `debug.answer_language = {language, source}`. 라우트는 그대로다.
  backend `AiEngineQueryResponse`는 모르는 필드를 무시하므로 영향이 없다.

### 4-3. MCP — `services/backend/.../mcp/`

- **결과·오류 메시지 영어화**(초안 — 지금 한국어 문구의 뜻과 자리표시자 `%s`·`%d`를 그대로 유지한다):

| 상수 | 영어 |
|---|---|
| INVALID_WORKSPACE | workspace must be the absolute path of the current working folder. |
| NO_PROJECTS | You have no whycode projects yet. Create one at %s first. |
| UNBOUND | This folder is not connected to a whycode project yet. Show the list below to the user, ask which project it is, then call bind_project(workspace, project_id). Confirm with the user even if there is only one project.\n%s |
| PROJECT_LIST_HEADER | whycode projects:\n%s |
| BOUND | Connected this folder to the whycode project "%s". |
| INVALID_PROJECT_ID | project_id is not a valid id. Check it with list_projects. |
| PROJECT_NOT_FOUND | Project not found. Check the project id with list_projects. |
| EMPTY_QUESTION | question is empty. |
| INVALID_HASH | hash must be a commit hash of 7-40 hex characters. Get it with git blame or git log. |
| INVALID_LANGUAGE (신규) | language must be "ko" or "en". |
| PLAN_LIMIT | You have reached the free plan's question limit. See plans at %s/pricing. |
| RATE_LIMITED | Too many questions. Try again in %d seconds. |
| TIMEOUT | whycode couldn't produce an answer within %d seconds. Please try again shortly. |
| GENERIC_ERROR | whycode failed to generate an answer. Please try again shortly. |
| AUTH_ERROR (`McpToolSpecifications`) | whycode couldn't verify your credentials. Try reconnecting. |
| INTERNAL_ERROR (`McpToolSpecifications`) | whycode hit an error while running the tool. Please try again shortly. |

- **`explain_commit(workspace, hash, language?)`**: 스키마에 `language`(`{"type":"string","enum":["ko","en"]}`, required 아님 —
  required는 workspace·hash 그대로). 해시를 먼저 검사하고, language는 비면 ko, 앞뒤 공백·대소문자 정리 후 ko/en만 받는다.
  그 밖의 값은 INVALID_LANGUAGE이고 ai-engine을 부르지 않는다. 질문 템플릿:
  - ko(지금 그대로): `커밋 %s가 왜 이렇게 바뀌었는지 근거와 함께 설명해줘`
  - en: `Explain, with evidence, why commit %s changed the way it did.`

  두 문장은 ai-engine 판정 테스트가 각각 ko·en으로 고정한다(서비스 간 계약).
- **도구 설명·서버 안내문**: 한·영 병기를 유지하고 양쪽의 "답변은 한국어이고 / Answers are in Korean" 문장만 바꾼다 —
  "답변은 질문한 언어(한국어·영어)를 따릅니다. ask는 사용자 언어로 질문을 쓰고, explain_commit의 language를 사용자 언어로 채우세요."
  / "Answers follow the language of the question (Korean or English): write ask questions in the user's language and set
  explain_commit's language to the user's language." `language` 인자 설명도 같은 병기 형식.
- **프롬프트**(`McpPromptSpecifications`) 영어화:
  - connect: 지금 문구와 같은 뜻 — list_projects로 목록을 받아 사용자에게 고르게 하고(하나뿐이어도 확인), 작업 폴더 절대 경로로 bind_project.
  - why: `Answer the following question with the whycode tools. For a specific line or snippet, get the commit hash with git blame and call explain_commit with language set to the question's language ("ko" or "en"); otherwise call ask with the question in the user's language. workspace is the absolute path of the current working folder. Reply to the user in the language of the question.\n\nQuestion: `
- `AiEngineQueryClient.ask` 시그니처는 바꾸지 않는다(테스트 스텁 약 100개). 언어는 질문 템플릿이 싣는다.
- `/mcp/setup` 안내(`clients/web-dashboard/src/components/landing/McpBody.tsx`): "답변은 한국어로 옵니다." →
  "답변은 질문한 언어(한국어 또는 영어)로 옵니다." / "Answers come back in Korean." → "Answers come back in the language of
  your question (Korean or English)."

### 4-4. 측정 — `eval/`

- **영어 짝 10개** `eval/golden/case-NN-en.yaml`: §3 실험의 번역 질문 그대로, `paired_with: case-NN`, `answer_language: en`,
  기대 근거·사실·규칙은 한국어판 그대로. 파일명이 `case-*.yaml`이어야 러너가 읽는다. 06번은 정답이 이슈 4개라 "which issues"로
  옮겼고, 45번은 실제 영어 사용자가 쓸 단수형("Which issue has…")을 그대로 둬 공동 1위를 잡는지 본다.
- **채점기**: summary+unknown_aspects의 한글 토큰 비율 ≥0.4 → ko, ≤0.15 → en, 사이는 mixed(실패).
  `runs_answer_language_mismatch`·`runs_language_decision_mismatch`·짝별 recall·사실 정답률 차이를 낸다.
  `validate_golden`이 `paired_with`·`answer_language`를 검사한다. `judge_prompt.md`(동결)는 건드리지 않는다.
  골든 파일이 늘면 `golden_version`이 바뀌어 compare.py가 경고한다(정상).
- **규모**: 짝 20개(한국어 10·영어 10) × 3회, B0(변경 전 이미지)·C(새 코드) 두 바퀴.
- **기준**(제안): S1 영어 런 100% 영어·한국어 런 100% 한국어 / S2 한국어 짝 지표 하락 없음(compare.py 허용선) /
  S3 짝별 recall 차이 평균 ≥ −0.07 / **S4 짝별 사실 정답률 차이 평균 ≥ −0.10** / S5 영어 런 structured_null 0.
  실험 기준으로는 S4가 아직 못 넘는다(−0.16). 결과는 `eval/improvement-log.md`.

---

## 5. 단계와 묶음

| 묶음 | 무엇을 | 맡는 에이전트 | 성공 기준 | 순서·병렬 |
|---|---|---|---|---|
| **0** | 본 지시를 통째로 영어로 번역해 §3과 같은 방식으로 영어 30회. 사실 정답률 차이가 0.10 안으로 줄면 언어 부분만 두 벌로 떼는 방식도 한 번 더 잰다 | 오케스트레이터(컨테이너 실험, 코드 변경 없음) | 영어 지시 구성을 정한다: 차이가 줄면 더 가벼운 쪽(언어 블록 분리 또는 전체 영어판), 줄지 않으면 덧붙임(2차 문구) 유지 | A1·B·C1과 병렬, A2의 지시 구성 전에 |
| **A1** | §4-1 언어 판정 모듈과 테스트 | implementer | 판정·import 테스트 → ai-engine 전체 `python -m pytest` | ai-engine 첫 묶음 |
| **A2** | §4-2 답변 경로·고정 문구·용어집·응답 계약 | implementer | 전체 pytest + 새 테스트(한국어 글자 그대로 가드, 영어 지시가 최종 호출에 있음, 제목·제안 문구·기록 정리·`language` 필드) | A1·0 뒤. 커밋 ① 테스트 mock 시그니처 ② **공용 가드**(용어집·치환·기록 정리) ③ 나머지 |
| **B** | §4-3 MCP 영어화 + `language` 인자 | spring-test-writer → implementer | `./gradlew test --tests "com.history.backend.mcp.*"` 빨강 → 초록, backend 전체 `./gradlew test` | A·C1과 병렬. 커밋 ① 메시지 ② 인자 |
| **C1** | §4-4 영어 짝 골든 + 채점기 + validate_golden | implementer | `python -m pytest eval/tests`, validate 0건, 최근 결과 재채점에서 한국어 런이 전부 ko | A·B와 병렬, E 전에 |
| **D** | 문서(§7) + `/mcp/setup` 두 줄 | implementer, 리뷰는 오케스트레이터 | 남은 "답변은 한국어"·"Answers are in Korean" 없음, 프론트 `npm run typecheck && npm run build` | A2·B 뒤 |
| **E** | B0 → 새 코드로 재빌드 → C, 채점·비교, 대시보드·Claude Code 수동 확인 | 오케스트레이터(OpenAI 비용 — 사용자 승인 후) | S1~S5. S4를 못 넘으면 0단계에서 정한 방향을 **후속 PR**로 | 마지막 |

묶음마다 그 묶음 파일만 리뷰(`branch-review`)하고, 지적은 등급과 추천을 붙여 한 번에 묻는다. 커밋은 사람이 한다.

**수동 확인 목록(E)**: 대시보드에서 영어 질문 → 영어 본문, 한국어 질문 → 지금과 같음, 영어 질문 뒤 `HT-12?` → 영어,
영어 인사 → "How can I help?"와 추천 카드, 영어로 내부 구현 질문 → "I can't share that information.".
Claude Code에서 영어 `ask` → 영어 답, `explain_commit` language=en → 영어 답, 폴더 미연결 등 오류 문구 영어, language 생략 시 지금과 같음.

---

## 6. 하지 않는 것과 한계

- 웹 화면 글자·`ui_language` 전달, backend 웹 오류 답 번역, Slack 주변 문구·`Locale.KOREA` 날짜, 지난 답 번역(§8)
- ko·en 밖의 언어, LLM 판정, 명시적 언어 요청("영어로 대답해줘")
- 검색 임계값·임베딩 변경, `judge_prompt.md`, `AiEngineQueryClient.ask` 시그니처
- 모델만 보는 문자열(도구 오류·맥락 카드·요약 지시·`…(생략)` 표시) — 영어 답의 원문 인용 안에 `…(생략)`이 남을 수 있다
- 영어 소유격(`HT-26's '…'`)의 직접 인용 검출 예외 — 관측 지표만 조금 흔들린다(`measurement.md`에 기록)
- **판정 한계**: 영어 용어 위주의 짧은 한국어 질문(`API rate limit 설정`)은 영어로, 영어 단어가 적은 영어 질문에 한국어 제목이
  붙으면(`Who created HT-26 그래프 생성?`) 한국어로 판정된다(§3-3)

## 7. 위험

1. **영어 답의 사실 정답률이 0.1~0.2 낮다**(§3). 0단계와 E의 S4로 다룬다.
2. **교차 언어 검색** — 실험에서는 차이가 없었다. E에서 계속 본다.
3. **섞인 대화** — 요청마다 따로 판정하고, 애매한 후속은 최근 사용자 질문의 언어를 따른다. 맥락 카드·누적 요약은 한국어일 수 있다(모델만 봄).
4. **영어 답이 한국어 화면에** — 배너·제목·날짜(ko-KR 표기)·추천 질문이 한국어다. 추천 질문은 한국어 문장이 그대로 질문으로 가므로 누르면 한국어로 답한다. 프론트 사이클 전까지 감수.
5. **MCP 에이전트가 `language`를 채우지 않음** — 기본 ko(지금 동작). Claude Code로 한 번 확인한다.

## 8. 프론트 사이클로 넘기는 것

D 묶음에서 [i18n.md](i18n.md)에 "앱 UI 언어 분리 인벤토리"로 옮겨 적는다(2026-10-11 탐색).

- 다국어 라이브러리 없음. 랜딩의 ko/en 구조(`LandingLanguageProvider`, `ht.lang` 저장, 브라우저 언어 감지, `LanguageMenu`)를 앱 전체 Provider로 넓혀 쓸 수 있다.
- **추천 질문 4장(`components/chat/suggestedQuestions.ts`)과 노드만 첨부할 때 보내는 "첨부한 항목에 대해 설명해줘."(`ChatPage.tsx`)는 한국어 문장이 그대로 질문으로 간다** — 화면 언어에 맞춰 바꿔야 영어 사용자가 영어 답을 받는다.
- backend 웹 오류 답은 `metadata.error_type`으로 화면이 표시할 때 번역한다(이미 저장된 한국어 오류 답도 함께 해결).
- `UI_LOCALE`(`lib/format.ts`)이 상수라 영어 답 안의 날짜도 ko-KR로 그려진다. `formatRelative`의 한국어 리터럴, 배너의 손으로 만든 날짜도 바꿀 곳이다.
- 규모: 앱 UI 약 53개 파일·515줄(채팅 44, 공통 틀 81, 소스 연결 161, 온보딩 41, 설정 62, 요금제 45, 그래프 41, OAuth 동의 35). `OnboardingPage.tsx`가 backend 영어 문구를 그대로 비교하는 곳이 있어 backend 문구 번역과 함께 움직여야 한다.
- 화면 언어 힌트 `ui_language`는 ai-engine이 받을 준비만 돼 있다. 연결할 때 `AiEngineQueryClient.ask`의 스텁 약 100개가 걸리므로 인자 객체로 묶는 것을 검토한다.

---

## 진행 체크리스트

- [ ] 0 — 영어 번역 지시 실험, 영어 지시 구성 확정
- [ ] A1 — 언어 판정 모듈
- [ ] A2 — 답변 경로·고정 문구·용어집·응답 계약
- [ ] B — MCP 영어화 + `explain_commit` language
- [ ] C1 — 영어 짝 골든 + 채점기
- [ ] D — 문서 + `/mcp/setup` 두 줄
- [ ] E — 측정(S1~S5) + 수동 확인
