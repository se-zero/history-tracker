# Embedding 설계 문서

## 목적

그래프 노드/엣지에 임베딩 벡터를 저장해 두 가지 목적에 사용한다.

1. **REFERENCE 엣지 자동 생성 (Layer 4)** — `MODIFIED.diffSummary` ↔ `Communication.body` 코사인 유사도가 임계값 이상인 쌍을 자동으로 연결
2. **쿼리 시 시맨틱 검색** — `Communication.body`·`Issue.title+body` 임베딩으로 자연어 질문에 대한 시맨틱 검색을 수행한다 (`comm_embedding`·`issue_embedding` 벡터 인덱스). refs 없이 DISCUSSED_IN / TRIGGERED_BY 엣지를 생성하는 것은 향후 과제로 남아 있다.

---

## 임베딩 모델

| 항목 | 값 |
|------|-----|
| 모델 | `text-embedding-3-large` (OpenAI) |
| 차원 | 1536 (`dimensions` 파라미터로 절삭 — 모델 기본은 3072) |
| 언어 | 한국어 + 영어 동시 지원 |
| 비용 | ~$0.02 / 1M 토큰 |
| 라이브러리 | `openai` (이미 설치됨, 추가 의존성 없음) |

임베딩 모델은 LLM(gpt-4o-mini)과 역할이 다르다.

- **LLM**: 텍스트를 읽고 답변 생성 (actor 판단, diff 요약, Slack 필터링)
- **임베딩 모델**: 텍스트 전체를 1536개 숫자 벡터로 압축. 역변환 불가.

3072이 아니라 1536으로 절삭해 쓰는 이유는 사전 조회에서 1536과 3072의 관련/무관 쌍 분리도 차이가
AUC +0.0026(95% CI [−0.005, +0.010])으로 **측정 해상도 안에서 구분되지 않았다** — 차원을 늘릴 실익이 없다.

---

## 임베딩 대상

| 노드/엣지 | 대상 텍스트 | 저장 위치 | 용도 |
|-----------|------------|---------|------|
| `Communication` 노드 | `body` | `Communication.embedding` | REFERENCE 엣지 생성 + 쿼리 시맨틱 검색 (`comm_embedding` 인덱스) |
| `MODIFIED` 엣지 | LLM이 생성한 `diffSummary` | `MODIFIED.embedding` | REFERENCE 엣지 생성 (벡터 인덱스 없음 — 브루트포스 비교) |
| `Issue` 노드 | `title + "\n\n" + body` | `Issue.embedding` | 쿼리 시맨틱 검색 (`issue_embedding` 인덱스); refs 없는 시맨틱 엣지 생성은 향후 |
| `DocumentSection` 노드 | `heading_path + "\n\n" + text` | `DocumentSection.embedding` | REFERENCE(ChangeSet→Document)·DESCRIBED_IN(Issue→Document) 엣지 생성 + 쿼리 시맨틱 검색 (`doc_section_embedding` 인덱스) |

> `MODIFIED` 엣지에는 `diffSummary`(사람이 읽는 텍스트)와 `embedding`(벡터)이 **둘 다** 저장된다.
> LLM은 임베딩 벡터가 아닌 원본 텍스트를 읽고 답변을 생성한다.

### `DocumentSection` 청킹 규칙 (`graph/document_chunker.py`)

`Communication`·`Issue`는 노드 하나에 임베딩 하나지만, `Document`(Notion 등 장기 문서)는 본문이
길어 통짜 임베딩이 다주제 문서에서 의미가 평균화된다. 그래서 본문을 `DocumentSection`으로 쪼개
섹션 단위로 임베딩한다(매칭은 섹션 단위, 그래프 엣지는 문서 단위 — `document_linker.py`).

- Notion normalizer가 보존한 Markdown 유사 heading(`#`~`###`)을 경계로 1차 분할한다.
  h1~h3 계층은 `상위 > 하위` 형태(`heading_path`)로 보존해 섹션 텍스트만으로 빠지는 맥락을 보탠다.
- 한 섹션이 `MAX_SECTION_CHARS`(1,500자)를 넘으면 문단 → 줄 → 문자 경계 순으로 다시 자른다
  (내용 손실보다 상한 보장이 우선).
- `MIN_SECTION_CHARS`(200자) 미만인 조각은 다음 섹션에 병합한다 — 짧은 전제·목차가 heading_path만
  남기고 사라지지 않게 한다.
- 이 모듈은 Neo4j·OpenAI를 모르는 순수 함수라 문서 구조 규칙만 단위 테스트로 고정할 수 있다.
- 임베딩은 이벤트 수신 시 `event_handler._handle_document`가 섹션 전체를 `embed_batch`로 1콜 처리한다
  (Communication/Issue처럼 콜당 1건이 아니라, 문서 하나가 여러 섹션이라 배치로 묶는다).

---

## 처리 흐름

### 실시간 — 이벤트 도착 시 (event_handler.py)

```
RabbitMQ 이벤트 도착
  ↓
Communication 이벤트 → embed_text(body)          → Communication.embedding 저장
ChangeSet 이벤트     → summarize_diff() → diffSummary
                     → embed_text(diffSummary)    → MODIFIED.embedding 저장
Issue 이벤트         → embed_text(title + body)   → Issue.embedding 저장
```

각 이벤트마다 API 1회 호출 (`embed_text` 사용).

### threshold 선정 근거

도입 초기 `text-embedding-3-small`로 실측한 결과, diffSummary(구조화된 포맷)와 Communication.body(구어체 대화)는 같은 내용이어도 임베딩 공간에서 거리가 있어 유사도가 전반적으로 낮게 나왔다.

| 케이스 | 유사도 (3-small 초기 실측) |
|--------|--------|
| 무관한 쌍 (점심 메뉴) | 0.15 |
| 관련 있는 쌍 (한국어, 낙관적 락) | 0.33 |
| 관련 있는 쌍 (한/영, JWT) | 0.40 |
| 동일 의미 한/영 | 0.57 |

초기값은 무관한 쌍(0.15)과 관련 있는 쌍(0.33~) 사이인 0.30으로 잡았고, 이후 실제 데이터 정답지 기반
스윕으로 엣지 타입별로 재조정했다 (`docs/measurement.md` 4.5, `eval/improvement-log.md`).
**임계값은 임베딩 모델에 종속**이라 위 표의 점수대는 3-large로 바꾼 지금은 그대로 적용되지 않는다 —
모델을 교체하면 전 임계값 재스윕이 전제다. 현행 채택값은 코드 상수가 단일 출처다
(`reference_builder.DEFAULT_THRESHOLD`, `issue_linker.DISCUSSED_IN_THRESHOLD`/`TRIGGERED_BY_THRESHOLD`,
`document_linker.DOCUMENT_REFERENCE_THRESHOLD`/`DESCRIBED_IN_THRESHOLD`).

`document_linker.py`의 두 임계값은 자체 실측이 아니라 **같은 성격의 기존 쌍에서 초기값을 물려받은
잠정값**이다 — Document는 도입 초기라 정답지가 아직 없다.

| 임계값 | 값 | 물려받은 쪽 | 근거 |
|---|---|---|---|
| `DOCUMENT_REFERENCE_THRESHOLD` (ChangeSet↔Document) | 0.44 | `reference_builder.DEFAULT_THRESHOLD`(ChangeSet↔Communication) | 둘 다 "diff 요약 대 텍스트" 비교 |
| `DESCRIBED_IN_THRESHOLD` (Issue↔Document) | 0.48 | `issue_linker.DISCUSSED_IN_THRESHOLD`(Issue↔Communication) | 둘 다 "텍스트 대 텍스트" 비교. `TRIGGERED_BY_THRESHOLD`(0.34, 이슈-코드diff)가 아니다 |

섹션 단위 비교가 통짜 비교보다 점수가 높게 나오는 경향이 있어, eval 재스윕 전까지는 위 값을
그대로 굳히지 않는다(`docs/notion-integration.md` §2-6).

---

### 배치 — REFERENCE 엣지 생성 (reference_builder.py)

```
별도 실행 (수동 또는 스케줄)
  ↓
Neo4j에서 MODIFIED.embedding 목록 조회
Neo4j에서 Communication.embedding 목록 조회
  ↓
occurredAt 차이 5일 이내인 쌍만 코사인 유사도 계산  ← 시간 윈도우 필터
  ↓
유사도 ≥ 0.44 → REFERENCE 엣지 생성 (confidence = 유사도)
```

이 5일 양방향 윈도우는 **ChangeSet↔Communication 전용**이다. `document_linker.py`(ChangeSet/Issue↔
Document)는 다른 윈도우를 쓴다 — 문서는 오래 살아 상한을 두지 않는 대신, 문서당 top-k(5) 컷으로
후보 폭증을 막는다:

```
[document.createdAt - 7일, ∞)  ← 하한만(문서가 쓰이기 전 활동은 근거로 보지 않음), 상한 없음
  ↓
문서당 top-5 매칭만 유지 (반대편 ChangeSet/Issue는 열어 둠 — 문서 하나가 여러 변경의 근거인 게 정상)
```

---

## 구현 파일

| 파일 | 역할 |
|------|------|
| `graph/embedder.py` | `embed_text()`, `embed_batch()`, `cosine_similarity()` |
| `graph/reference_builder.py` | REFERENCE(ChangeSet↔Communication) 엣지 배치 생성, Communication 임베딩 보정 |
| `graph/document_chunker.py` | Document 본문을 `DocumentSection` 단위로 청킹(순수 함수, Neo4j·OpenAI 미의존) |
| `graph/document_linker.py` | REFERENCE(ChangeSet↔Document)·DESCRIBED_IN(Issue↔Document) 엣지 배치 생성 |
| `graph/event_handler.py` | 이벤트 처리 시 임베딩 호출 |

---

## REFERENCE 엣지 시간복잡도 및 개선 방향

현재 구현은 브루트포스 + 시간 윈도우 필터:

```
O(M × n × D)
  M = MODIFIED 엣지 수
  n = 시간 윈도우(5일) 내 Communication 수
  D = 벡터 차원 (1536)
```

### 개선 방향 — Neo4j Vector Index

Neo4j 5.x HNSW 벡터 인덱스(`comm_embedding`, `issue_embedding`, `doc_section_embedding`)는 **이미 생성·사용 중**이다 — 기동 시 `ensure_vector_indexes()`가 생성하고, 용도는 **쿼리 시 시맨틱 검색**이다 (`tools/queries/discovery.py`가 Communication·Issue를, `tools/queries/document.py`가 DocumentSection을 `db.index.vector.queryNodes`로 검색). `doc_section_embedding`은 Notion 커넥터가 추가한 세 번째 인덱스다(`docs/notion-integration.md`). 다만 **REFERENCE 엣지 배치 빌더(`reference_builder.py`)는 이 인덱스를 쓰지 않고 여전히 브루트포스 + 시간 윈도우**다.

> **project_id 후필터 (over-fetch)**: `db.index.vector.queryNodes`는 전역 top-K만 반환하고 project_id 사전 필터가 불가능하다. 단일 Neo4j에 여러 프로젝트가 섞여 있으므로, `top_k`의 배수만큼 넉넉히 가져온 뒤 `project_id`로 후필터해 잘라낸다.

```cypher
-- 인덱스 정의 (기동 시 ensure_vector_indexes()가 생성)
CREATE VECTOR INDEX comm_embedding IF NOT EXISTS
FOR (c:Communication) ON (c.embedding)
OPTIONS { indexConfig: {
  `vector.dimensions`: 1536,
  `vector.similarity_function`: 'cosine'
}}
```

REFERENCE 엣지 빌더도 vector index로 옮기면 Python 루프 없이 DB 안에서 처리할 수 있으나 **아직 미채택**이다. 도입 시 예시:

```cypher
-- REFERENCE 엣지 생성 쿼리 (미채택 향후안)
MATCH (cs:ChangeSet)-[m:MODIFIED]->(f:File)
WHERE m.embedding IS NOT NULL
CALL db.index.vector.queryNodes('comm_embedding', 10, m.embedding)
YIELD node AS comm, score
WHERE score >= 0.44 AND comm.project_id = cs.project_id
MERGE (cs)-[r:REFERENCE]->(comm)
SET r.confidence = score
```

| 방법 | 시간복잡도 | 설명 |
|------|-----------|------|
| 브루트포스 | O(M × N) | 전체 쌍 비교 |
| 시간 윈도우 + 브루트포스 | O(M × n) | 5일 이내 쌍만 비교 (현재 REFERENCE 빌더) |
| Neo4j Vector Index (HNSW) | O(M × log N) | DB 내부 근사 탐색 (쿼리 검색엔 적용, REFERENCE 빌더엔 미적용) |

> HNSW는 근사 알고리즘이므로 극히 드물게 유사한 쌍을 놓칠 수 있으나, 이 프로젝트 특성상 허용 가능한 수준.
