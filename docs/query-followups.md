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

- [ ] `agent/orchestrator.py`: 복구 불가능한 클라이언트 오류(4xx `BadRequestError` 등)는
      삼키지 말고 전파한다 — 일시 오류(429·5xx·타임아웃)와 구분해서 처리
- [ ] `routers/query.py`: LLM 호출 실패 시 200 대신 5xx 또는 명시적 오류 필드 반환 검토
      (backend 프록시·프론트 채팅의 오류 처리 계약 확인 필요)
- [ ] `eval/runner.py`: `structured=null` 응답을 실패로 집계하고 exit code에 반영
      (무효 측정이 조용히 점수에 섞이는 것 방지)
- [ ] (선택) LLM 오류율을 메트릭/헬스에 노출해 운영 중 조기 감지

---

## 3. 그래프 레벨 전달 사항 (2026-07-14 풀 런 진단 결과 — 그래프 담당자용)

풀 런(`results/20260714T061708Z`)의 금지사실·오염 케이스를 트랜스크립트로 추적한 결과,
아래 두 건은 e2e가 아니라 **그래프 엣지 레벨**에서 고쳐야 한다.

- [ ] **HT-102 → message:1781533811 DISCUSSED_IN 엣지 검토** — case-39 오염의 직접 원인.
      글로벌 검색 이슈(HT-102)에 무관한 6/16 브랜치 수집 논의 스레드가 연결돼 있어,
      `get_issue_context(HT-102)`가 3/3런 이 스레드를 끌어옴. false positive면 precision
      라벨셋(`eval/edge_labels/`)에 `irrelevant`로 추가하고 엣지 제거 검토.
- [ ] **스레드 단위 DISCUSSED_IN의 해상도 한계** (case-33) — 한 스레드에 여러 이슈 논의가
      섞이면 스레드→이슈 연결이 오귀속을 유도한다. 수집 트리거 메시지(HT-75 관련)가 있는
      스레드가 HT-54에만 연결돼, 모델이 검색 결과에 HT-75가 있었는데도 HT-54로 귀속함.
      메시지 단위 연결 또는 스레드-복수 이슈 연결의 설계 검토 필요.

참고 — 당장 조치 없음으로 분류한 것: case-30(정답이 검색에 있었고 런 변동성),
case-16(모델이 시사적 근거에서 단정 서술 — judge 엄격성 경계, 골든 재검토 후보).
