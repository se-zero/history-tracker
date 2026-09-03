# History Tracker

GitHub, Jira, Slack에 흩어진 협업 데이터를 하나의 지식 그래프로 연결해 코드 변경의 진짜 이유와 의사결정 맥락을 AI가 추론하는 GraphRAG 플랫폼입니다.

## 문제 의식

> "이 코드가 왜 이렇게 바뀐 거지?"

PR, 이슈, Slack 대화, Jira 티켓이 각각 따로 존재해서 코드 변경의 배경을 파악하려면 여러 툴을 직접 뒤져야 합니다. 이 프로젝트는 그 데이터를 하나의 지식 그래프로 묶고, 자연어 질문으로 맥락을 찾을 수 있게 합니다.

## 아키텍처

```
[GitHub / Jira / Slack]
        ↓
  pipeline-worker     ← 데이터 수집·정규화 (Spring Boot)
        ↓  RabbitMQ
    ai-engine         ← 그래프 구축, LLM 처리, 임베딩 (Python/FastAPI)
        ↓
      Neo4j           ← 지식 그래프 + 벡터 인덱스
```

## 기술 스택

| 구성요소 | 기술 |
|--------|------|
| 데이터 수집 | Spring Boot (Java), GitHub API, Jira API, Slack API |
| 메시지 큐 | RabbitMQ |
| AI 처리 | Python/FastAPI, OpenAI (질의 gpt-5.4-mini · 그래프 구축 gpt-4o-mini), text-embedding-3-large (1536차원 절삭) |
| 그래프 DB | Neo4j (벡터 인덱스 포함) |

## 핵심 기능

### 크로스소스 지식 그래프 구축
GitHub 커밋/PR/이슈, Jira 티켓, Slack 메시지를 수집해 Neo4j 지식 그래프로 변환합니다. 각 데이터 소스의 사용자를 동일인 판단 파이프라인으로 통합해 하나의 Actor 노드로 연결합니다.

### LLM 기반 diff 요약
커밋의 코드 변경(unified diff)을 GPT-4o-mini가 읽고 사람이 이해할 수 있는 요약문(diffSummary)으로 변환합니다. 이 요약문이 MODIFIED 엣지에 저장되어 코드 변경의 의미를 그래프에 보존합니다.

### 시맨틱 크로스소스 연결
각 노드의 텍스트를 text-embedding-3-large(1536차원으로 절삭)로 임베딩해 코사인 유사도 기반으로 REFERENCE 엣지를 자동 생성합니다. Jira 키나 PR 번호를 명시하지 않아도 커밋과 관련 Slack 대화를 의미적으로 연결합니다.

### Slack 메시지 필터링
룰 기반 + LLM 2단계 필터로 Slack 메시지 중 지식 그래프에 저장할 가치가 있는 메시지만 선별합니다. 단순 승인 메시지, 잡담, 무관한 프로젝트 내용을 제거합니다.

### GraphRAG 질의응답 (개발 예정)
지식 그래프를 기반으로 자연어 질문에 답변합니다. LLM Tool Calling으로 적절한 Cypher 쿼리를 선택해 "이 파일을 마지막으로 수정한 이유가 뭐야?", "이 이슈와 관련된 커밋이 있어?" 같은 질문에 답합니다.

## 그래프 스키마

### 노드 (6종)
- **Actor**: GitHub·Jira·Slack의 사용자. 플랫폼을 넘어 동일인을 하나의 노드로 통합
- **Issue**: 이슈 트래커의 작업 단위 (Jira·Linear·Asana·ClickUp 이슈, GitHub 이슈)
- **Communication**: 대화 메시지 (Slack·Discord·Google Chat)
- **PullRequest**: GitHub PR
- **ChangeSet**: GitHub 커밋
- **File**: 저장소 내 파일

### 주요 관계
- `MODIFIED` — 커밋이 파일을 변경 (diffSummary, embedding 포함)
- `REFERENCE` — 커밋과 Slack 대화의 시맨틱 연결 (벡터 유사도 기반)
- `DISCUSSED_IN` — Jira 이슈가 특정 대화에서 논의됨
- `TRIGGERED_BY` — 이슈에 대응하는 커밋
- `CONTAINS` — PR에 포함된 커밋

자세한 스키마는 [docs/graph-schema.md](docs/graph-schema.md)를 참고하세요.

## 상세 설계 문서

- [docs/graph-schema.md](docs/graph-schema.md) — 노드·관계 정의 및 관계 생성 기준
- [docs/actor-node-design.md](docs/actor-node-design.md) — Actor 동일인 판단 파이프라인
- [docs/embedding-design.md](docs/embedding-design.md) — 임베딩 모델 선택, 벡터 인덱스, REFERENCE 엣지 생성 흐름
