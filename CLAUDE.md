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
        ↓  수집
  pipeline-worker     ← 데이터 수집·정규화·NormalizedEvent 생성 (Spring Boot :8081)
        ↓  AMQP (RabbitMQ: history.exchange / history.events)
    ai-engine         ← GraphRAG 에이전트, 임베딩, Neo4j 그래프 구축 (Python/FastAPI :8000)
        ↓
      Neo4j           ← 지식 그래프 저장소
        ↑  /query · /graph (HTTP)
    backend           ← 사용자·프로젝트·대화 관리, 프론트엔드 API (Spring Boot :8080)
        ↑
  web-dashboard       ← React/Vite 웹 프론트엔드 (:5173)

  PostgreSQL          ← backend(사용자·프로젝트·대화)와
                         pipeline-worker(checkpoints, webhook_deliveries)가 공유하는 RDB
```

데이터 흐름은 두 갈래다.

1. **수집(쓰기)**: pipeline-worker가 외부 API/웹훅으로 수집·정규화한 `NormalizedEvent`를
   RabbitMQ에 발행하고, ai-engine consumer가 이를 받아 Neo4j 그래프로 구축한다.
   수집 큐가 잠잠해지면(유휴 디바운스) ai-engine이 소스 간 시맨틱 엣지(Layer 4)를 자동 빌드하며,
   웹 대시보드의 '그래프 재구축' 버튼으로 수동 트리거도 가능하다.
2. **질의(읽기)**: 프론트엔드 → backend → ai-engine(`/query`, `/graph/*`) → Neo4j 순으로
   자연어 질문에 답한다. backend는 사용자/프로젝트/대화를 관리하며 ai-engine을 프록시한다.

## 서비스 구성

| 서비스 | 기술 | 역할 |
|--------|------|------|
| `pipeline-worker` | Spring Boot :8081 | 외부 API/웹훅 수집, NormalizedEvent 변환, RabbitMQ 발행. checkpoint 기반 증분 수집 |
| `RabbitMQ` | Message Broker | NormalizedEvent 큐잉 (`history.exchange` / `history.events`, routing `event.#`) |
| `ai-engine` | Python/FastAPI :8000 | GraphRAG tool-calling 에이전트(OpenAI), 임베딩, Neo4j 그래프 구축. RabbitMQ consumer 겸 HTTP 질의 API |
| `Neo4j` | Graph DB | 지식 그래프 저장소 (vector index 포함) |
| `backend` | Spring Boot :8080 | 사용자·프로젝트·연동·대화 관리, RDB, 프론트엔드 API. ai-engine/pipeline-worker 연동 |
| `PostgreSQL` | RDB | backend와 pipeline-worker가 공유 (Flyway migration으로 스키마 관리) |
| `web-dashboard` | React/Vite :5173 | 사용자 웹 프론트엔드 (onboarding, sources, graph, chat) |


## 실행 방법 (로컬, Docker Compose)

전체 스택은 `infra/docker`의 docker-compose로 기동한다.

```bash
cd infra/docker
./dev.sh up -d --build    # docker compose --profile app 래퍼
./dev.sh logs -f backend
./dev.sh ps
./dev.sh down
```

- 모든 환경변수는 `infra/docker/.env` 한 곳에 모인다 (`.env`는 gitignore).
- 컨테이너: postgres, neo4j, rabbitmq, ai-engine, backend, pipeline-worker, web-dashboard.
- 필수 키: `BACKEND_CREDENTIAL_KEY`(32-byte Base64), `INTERNAL_SERVICE_TOKEN`(backend·pipeline-worker 공유),
  `OPENAI_API_KEY`, GitHub App OAuth 값(`GITHUB_APP_*`, `GITHUB_CLIENT_*`), Atlassian OAuth 값(`ATLASSIAN_CLIENT_*`, `ATLASSIAN_REDIRECT_URI`).
- 개별 서비스 빌드/테스트 명령은 각 서비스의 CLAUDE.md를 참고한다.

## 코딩 규칙

서비스별 상세 규칙은 각 서비스의 CLAUDE.md를 따른다.

- `services/backend/CLAUDE.md` — 패키지 구조, 외부 연동, migration, 주석 규칙
- `services/pipeline-worker/CLAUDE.md` — 패키지 구조, 수집/웹훅 흐름, checkpoint, 설정
- `services/ai-engine/CLAUDE.md` — 패키지 구조, 실행/테스트, facade·OpenAI 클라이언트 규칙
- `clients/web-dashboard/CLAUDE.md` — 디렉터리 구조, 상태(query-hook)·스타일 컨벤션, 검증 명령

공통(Spring 서비스):
- 패키지는 기능 단위로 나누고 `controller` / `service` / `repository` / `domain` / `config`를 둔다.
- `controller`에 비즈니스 로직을 두지 않는다 — `service`에만 작성한다.
- DB 스키마는 Flyway migration으로 관리하고 JPA `ddl-auto`는 `validate`를 사용한다.

## 참고 문서

`docs/`에 상세한 사양이 있으며, 필요할 때 Claude가 이를 읽는 구조다.

- `docs/graph-schema.md` - 지식 그래프 노드, 관계 정의
- `docs/data-collection.md` - pipeline-worker의 플랫폼별 수집·정규화·checkpoint 전략
- `docs/actor-node-design.md` - Actor 동일인 판단 파이프라인 상세 설계 (스코어링 로직, LLM 프롬프트, Neo4j 쿼리)
- `docs/actor-manual-merge.md` - Actor 수동 병합·분리 설계 (ActorDecision 영속화, resolver veto, unmerge/split)
- `docs/embedding-design.md` - 임베딩 모델 선택, 대상 노드/엣지, REFERENCE 엣지 생성 흐름, Neo4j Vector Index 도입 계획
- `docs/DB.md` - backend PostgreSQL 테이블 정의 및 관계도 (Flyway V1~)
- `docs/tools.md` - ai-engine의 LLM tool-calling 도구 레퍼런스 (계약·반환·동작, 코드 위치 포인터)
- `docs/query-quality-issues.md` - GraphRAG 쿼리 품질 이슈 분석
