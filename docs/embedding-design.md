# Embedding 설계 문서

## 목적

그래프 노드/엣지에 임베딩 벡터를 저장해 두 가지 목적에 사용한다.

1. **REFERENCE 엣지 자동 생성 (Layer 4)** — `MODIFIED.diffSummary` ↔ `Communication.body` 코사인 유사도가 임계값 이상인 쌍을 자동으로 연결
2. **미래 시맨틱 연결** — `Issue.title + body` 임베딩을 저장해두면 나중에 DISCUSSED_IN / TRIGGERED_BY 엣지를 refs 없이도 생성 가능

---

## 임베딩 모델

| 항목 | 값 |
|------|-----|
| 모델 | `text-embedding-3-small` (OpenAI) |
| 차원 | 1536 |
| 언어 | 한국어 + 영어 동시 지원 |
| 비용 | ~$0.02 / 1M 토큰 |
| 라이브러리 | `openai` (이미 설치됨, 추가 의존성 없음) |

임베딩 모델은 LLM(gpt-4o-mini)과 역할이 다르다.

- **LLM**: 텍스트를 읽고 답변 생성 (actor 판단, diff 요약, Slack 필터링)
- **임베딩 모델**: 텍스트 전체를 1536개 숫자 벡터로 압축. 역변환 불가.

---

## 임베딩 대상

| 노드/엣지 | 대상 텍스트 | 저장 위치 | 용도 |
|-----------|------------|---------|------|
| `Communication` 노드 | `body` | `Communication.embedding` | REFERENCE 엣지 생성 |
| `MODIFIED` 엣지 | LLM이 생성한 `diffSummary` | `MODIFIED.embedding` | REFERENCE 엣지 생성 |
| `Issue` 노드 | `title + "\n\n" + body` | `Issue.embedding` | 미래 DISCUSSED_IN / TRIGGERED_BY 시맨틱 연결 |

> `MODIFIED` 엣지에는 `diffSummary`(사람이 읽는 텍스트)와 `embedding`(벡터)이 **둘 다** 저장된다.
> LLM은 임베딩 벡터가 아닌 원본 텍스트를 읽고 답변을 생성한다.

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

### 배치 — REFERENCE 엣지 생성 (reference_builder.py)

```
별도 실행 (수동 또는 스케줄)
  ↓
Neo4j에서 MODIFIED.embedding 목록 조회
Neo4j에서 Communication.embedding 목록 조회
  ↓
occurredAt 차이 5일 이내인 쌍만 코사인 유사도 계산  ← 시간 윈도우 필터
  ↓
유사도 ≥ 0.75 → REFERENCE 엣지 생성 (confidence = 유사도)
```

---

## 구현 파일

| 파일 | 역할 |
|------|------|
| `graph/embedder.py` | `embed_text()`, `embed_batch()`, `cosine_similarity()` |
| `graph/reference_builder.py` | REFERENCE 엣지 배치 생성, Communication 임베딩 보정 |
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

### 개선 — Neo4j Vector Index (Neo4j 연동 시 적용 예정)

Neo4j 5.x의 HNSW 기반 벡터 인덱스를 사용하면 Python 루프 없이 DB 안에서 처리된다.

```cypher
-- 인덱스 생성 (최초 1회)
CREATE VECTOR INDEX comm_embedding
FOR (c:Communication) ON (c.embedding)
OPTIONS { indexConfig: {
  `vector.dimensions`: 1536,
  `vector.similarity_function`: 'cosine'
}}

-- REFERENCE 엣지 생성 쿼리
MATCH (cs:ChangeSet)-[m:MODIFIED]->(f:File)
WHERE m.embedding IS NOT NULL
CALL db.index.vector.queryNodes('comm_embedding', 10, m.embedding)
YIELD node AS comm, score
WHERE score >= 0.75
MERGE (cs)-[r:REFERENCE]->(comm)
SET r.confidence = score
```

| 방법 | 시간복잡도 | 설명 |
|------|-----------|------|
| 브루트포스 | O(M × N) | 전체 쌍 비교 |
| 시간 윈도우 + 브루트포스 | O(M × n) | 5일 이내 쌍만 비교 (현재) |
| Neo4j Vector Index (HNSW) | O(M × log N) | DB 내부 근사 탐색 (예정) |

> HNSW는 근사 알고리즘이므로 극히 드물게 유사한 쌍을 놓칠 수 있으나, 이 프로젝트 특성상 허용 가능한 수준.
