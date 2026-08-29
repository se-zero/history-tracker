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


## 실행 방법 (Docker Compose)

전체 스택은 `infra/docker`의 docker-compose로 기동한다.
**로컬과 배포는 실행 스크립트가 다르다** — 같은 base에 다른 오버라이드를 얹기 때문이다.

```bash
cd infra/docker

# 로컬 개발 — 인프라·앱 포트를 전부 연다
./dev.sh up -d --build
./dev.sh logs -f backend
./dev.sh ps
./dev.sh down

# 배포(서버) — 웹(80)만 열고, 자원 상한·재시작·로그 로테이션이 붙는다
./prod.sh up -d --build
./prod.sh ps
```

`./dev.sh`는 `docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile app` 래퍼이고,
`./prod.sh`는 `dev.yml` 자리에 `prod.yml`이 들어간다. 배포 서버에서 실수로 `./dev.sh`를 쓰면
인증 없는 pipeline-worker 엔드포인트(`/api/v1/collect`·`/api/v1/raw`)까지 외부에 열린다.

- 모든 환경변수는 `infra/docker/.env` 한 곳에 모인다 (`.env`는 gitignore).
- 컨테이너: postgres, neo4j, rabbitmq, ai-engine, backend, pipeline-worker, web-dashboard.
- **compose는 3분할이다** — base(`docker-compose.yml`)는 서비스 정의만 갖고, 호스트 포트는
  환경별 오버라이드가 소유한다(`docker-compose.dev.yml` 전체 노출 / `docker-compose.prod.yml` 웹만).
  오버라이드는 `ports`를 덮어쓰지 않고 **이어붙이기** 때문에, base에 포트를 두면 배포에서 닫을 수 없다.
  그래서 `docker compose`를 직접 치면 어떤 포트도 열리지 않는다 — 항상 위 두 스크립트를 쓴다.
- 실사용 배포 절차는 `docs/deployment.md`를 따른다.
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
각 문서의 완료·미완은 [`docs/README.md`](docs/README.md)를 본다.

- `docs/graph-schema.md` - 지식 그래프 노드, 관계 정의
- `docs/data-collection.md` - pipeline-worker의 플랫폼별 수집·정규화·checkpoint 전략
- `docs/normalized-event.md` - pipeline-worker ↔ ai-engine 수집 계약 (nodeType별 properties·refs·source 표기). **새 커넥터 작업의 체크리스트**
- `docs/integration-abstraction.md` - 신규 integration(Linear·Teams·Notion 등) 추가를 위한 provider 추상화 계획 — 아키타입 분류, 서비스별 SPI 설계, 진행 순서
- `docs/discord-integration.md` - Discord 커넥터(**대화 아키타입 1호**) 계획 — 봇 토큰 수집 모델, 선택 단계 없는 연결(Slack형), snowflake 증분, MESSAGE_CONTENT intent 게이트, 선행 공용 변경(revoke 시그니처)
- `docs/google-chat-integration.md` - Google Chat 커넥터(**대화 아키타입에서 A4 다단 선택(1단 space)을 처음 검증**) — 코드 작업 완료(backend·pipeline-worker·web-dashboard, 선행 PR 2건 포함). Workspace 계정 게이트 실측·우리 앱 실기동(연결·스페이스 선택·초기 수집) 확인. PR 머지 웹훅 증분·1시간 토큰 갱신은 실기동하지 않음. Chat 앱 구성 강제 여부 등은 문서 §12에 미확인
- `docs/teams-integration.md` - MS Teams 커넥터 계획(**착수 보류 — 유료 테넌트·관리자 동의 필요로 2호로 연기**) — Graph API 조사 근거의 결정 사항, 1단 team 선택, 정렬 기반 증분 전략, 선행 공용 변경(webhook 토큰 확보 일반화)
- `docs/slack-marketplace.md` - Slack 마켓플레이스 등재(public-readiness §0-3 D 트랙) 실행 계획 — `/why-code` 슬래시 커맨드, Events API 라이프사이클(`app_uninstalled`·`tokens_revoked`), bot 토큰 자격증명 이중화, 심사 제출물·리스크 등록부
- `docs/notion-integration.md` - Notion 커넥터(**문서 아키타입 1호 — 유일하게 ai-engine 신규 설계가 선행하는 예외**) 계획 — `Document`/`DocumentSection` 노드 설계, 섹션 단위 청킹·임베딩, Layer 4 시간 윈도우 재설계, 선행 공용 변경(REFERENCE의 text/semantic 분리), PR 4분할
- `docs/actor-node-design.md` - Actor 동일인 판단 파이프라인 상세 설계 (스코어링 로직, LLM 프롬프트, Neo4j 쿼리)
- `docs/actor-manual-merge.md` - Actor 수동 병합·분리 설계 (ActorDecision 영속화, resolver veto, unmerge/split)
- `docs/jira-personal-data-policy.md` - Jira 개인정보 보고 정책 — 보고 사이클, closed/access_lost 삭제 규칙, 배포 시 봇 계정 등록 절차
- `docs/embedding-design.md` - 임베딩 모델 선택, 대상 노드/엣지, REFERENCE 엣지 생성 흐름, Neo4j Vector Index 도입 계획
- `docs/DB.md` - backend PostgreSQL 테이블 정의 및 관계도 (Flyway V1~)
- `docs/deployment.md` - 실사용 배포 가이드 — 호스트 사양(Proxmox VM), `./prod.sh` 절차, OAuth 콜백 9종·GitHub webhook 등록 체크리스트, 자원 상한의 근거. **배포 관련 작업 전에 읽는다**
- `docs/deployment-followups.md` - 배포 경로 후속 작업 — RabbitMQ 자격증명 URL 분리, 터널 실기동 검증(도메인 대기), pipeline-worker 인바운드 인증 검토
- `docs/tools.md` - ai-engine의 LLM tool-calling 도구 레퍼런스 (계약·반환·동작, 코드 위치 포인터)
- `docs/query-quality-issues.md` - GraphRAG 쿼리 품질 이슈 분석
- `docs/measurement.md` - GraphRAG 정량 측정(eval) 가이드 — 그래프·응답 품질 개선을 숫자로 검증하는 방법
- `docs/DESIGN.md` - 디자인 시스템(팔레트·타이포·모션·랜딩). **UI 작업 전에 읽고 모든 시각 결정을 여기서 파생시킨다**
- `docs/i18n.md` - 다국어 준비 메모(**언어 분리 착수 전**) — 시각 표시 작업이 세워 둔 계약. 로캘(UI 언어)과 타임존(기기 설정)은 독립 축이며, 언어 기본값을 위치로 정하더라도 시각 표시에 위치를 끌어들이지 않는다. 서버는 UTC ISO만 내보내고 표시 변환은 프론트가 전담
