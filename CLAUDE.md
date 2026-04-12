# CLAUDE.md

## 프로젝트 한 줄 요약

파편화된 협업 툴(Jira, GitHub, Slack)의 데이터를 지식 그래프로 연결해,
코드 변경의 **진짜 이유**와 **의사결정 맥락**을 AI가 추론하는 GraphRAG 플랫폼.

## 핵심 문제

> "이 코드가 왜 이렇게 바뀐 거지?"

PR, 이슈, Slack 대화, Jira 티켓이 각각 따로 존재해서 코드 변경의 배경을 파악하려면
여러 툴을 직접 뒤져야 한다. 이 프로젝트는 그 데이터를 하나의 지식 그래프로 묶고,
자연어 질문으로 맥락을 찾을 수 있게 한다.

## 전체 아키텍처

```
[GitHub / Jira / Slack]
        ↓
  pipeline-worker     ← 데이터 수집·정규화·NormalizedEvent 생성 (Spring Boot :8081)
        ↓  AMQP (RabbitMQ)
     RabbitMQ         ← 이벤트 큐 (history.exchange / history.events)
        ↓
    ai-engine         ← GraphRAG, 임베딩, Neo4j 그래프 구축 (Python/FastAPI :8000)
        ↓
      Neo4j           ← 지식 그래프 저장소
        ↑
    backend           ← 사용자/프로젝트 관리, 프론트엔드 API (Spring Boot :8080)
        ↑
[Web / VSCode Extension / Slack Bot / CLI]
```

## 서비스 구성

| 서비스 | 기술 | 역할 |
|--------|------|------|
| `pipeline-worker` | Spring Boot | 외부 API 수집, NormalizedEvent 변환, RabbitMQ 발행 |
| `RabbitMQ` | Message Broker | NormalizedEvent 큐잉 (`history.events`) |
| `ai-engine` | Python/FastAPI | GraphRAG 로직, 임베딩, Neo4j 그래프 구축 |
| `backend` | Spring Boot | 사용자·프로젝트 관리, RDB, 프론트엔드 API |

## 코딩 규칙

### pipeline-worker (Spring Boot)
- 패키지 구조: `controller` / `service` / `repository` / `domain` / `config`
- `controller`에 비즈니스 로직 금지 — `service`에만 작성


## 참고 문서
`docs/`에 상세한 사양이 있으며, 필요할 때 Claude가 이를 읽는 구조
- `docs/graph-schema.md` - 지식 그래프 노드, 관계 정의
