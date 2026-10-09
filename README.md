# whycode

코드가 **왜** 그렇게 바뀌었는지 답하는 GraphRAG 서비스입니다. GitHub·이슈 트래커·메신저·문서에 흩어진 협업 기록을
하나의 지식 그래프로 묶고, 자연어 질문에 근거와 함께 답합니다.

- 서비스: https://why-code.com — 설치 안내: [코딩 에이전트 연결](https://why-code.com/mcp/setup) · [Slack](https://why-code.com/slack)
- 저장소 이름과 코드 안의 식별자는 옛 이름 `history-tracker`(History Tracker)를 그대로 씁니다. 서비스명만 whycode로 바뀌었습니다.

## 무엇을 하나

> "이 코드가 왜 이렇게 바뀐 거지?"

변경의 이유는 커밋 메시지에만 있지 않습니다. PR 리뷰, 이슈 티켓, 메신저의 논의, 설계 문서에 나뉘어 있고, 몇 달이 지나면
어디를 봐야 할지조차 잊힙니다. whycode는 이 기록들을 수집해 **커밋·PR·이슈·대화·문서·사람**을 잇는 그래프로 만들고,
질문을 받으면 그 그래프를 탐색해 답합니다.

이런 질문에 답합니다.

- "`PaymentRetryPolicy.java`는 왜 생겼어?"
- "결제 재시도 간격을 3초에서 10초로 바꾼 이유가 뭐야?"
- "PAY-203 티켓은 어떤 논의 끝에 닫혔고, 어느 커밋이 대응했어?"

답은 세 부분으로 옵니다. 한국어 답변, 답의 근거가 된 기록(커밋·PR·이슈·대화·문서 카드), 그리고 기록만으로는 확인되지
않은 측면. 근거가 없는 내용은 답에 넣지 않고 "확인되지 않았다"고 말합니다.

## 쓰는 방법 세 가지

| 입구 | 어떻게 | 비고 |
|------|--------|------|
| 웹 대시보드 | why-code.com에 GitHub로 로그인 → 프로젝트 만들기 → 저장소·소스 연결 → 채팅 | 대화가 저장되고, 근거 카드와 그래프 화면이 있다 |
| Slack | 워크스페이스에 앱을 설치한 뒤 채널에서 `/why-code 질문` | 답은 질문한 사람에게만 보이고 대화는 저장하지 않는다 |
| 코딩 에이전트(MCP) | 에이전트에 MCP 서버 `https://why-code.com/mcp`를 등록하고 브라우저에서 허용 — 클라이언트별 절차는 [why-code.com/mcp/setup](https://why-code.com/mcp/setup) | 폴더마다 프로젝트를 한 번 고르면 그 폴더에서 바로 묻는다 |

## 연결할 수 있는 데이터

| 종류 | 소스 | 그래프에 들어가는 것 |
|------|------|----------------------|
| 코드 | GitHub | 커밋(파일별 변경 요약 포함), PR, GitHub 이슈 |
| 이슈 | Jira, Linear, Asana, ClickUp | 티켓과 상태·담당자·본문 |
| 대화 | Slack, Discord, Google Chat | 메시지와 스레드 — 잡담·승인 같은 노이즈는 2단계 필터로 거른다 |
| 문서 | Notion | 페이지를 섹션 단위로 |

연결은 각 서비스의 OAuth 동의(GitHub는 App 설치)로 하고, 이후에는 웹훅과 주기 수집으로 증분 반영합니다. 새 소스를 붙이는
순서는 [docs/integration-abstraction.md](docs/integration-abstraction.md)에 있습니다.

## 어떻게 동작하나

```
수집    pipeline-worker가 소스별 API·웹훅에서 기록을 받아 공통 형식(NormalizedEvent)으로 바꿔 RabbitMQ에 발행한다
그래프  ai-engine이 이를 받아 Neo4j에 노드·엣지로 쓰고, 수집이 잠잠해지면 소스를 가로지르는 시맨틱 엣지를 만든다
질의    LLM 에이전트가 그래프 조회 도구로 질문에 맞는 기록을 찾아 근거와 함께 답한다
```

핵심 아이디어는 넷입니다.

- **소스를 가로지르는 하나의 그래프.** 커밋·PR·이슈·대화·문서·사람이 한 그래프에 있고, 프로젝트별로 격리됩니다.
- **변경 요약.** 커밋의 diff를 LLM이 읽어 파일별 변경 요약을 만들어 엣지에 저장합니다. "무엇이 바뀌었나"를 사람의 말로 보존합니다.
- **시맨틱 연결.** 티켓 번호나 PR 번호를 적지 않은 기록도 임베딩 유사도(필요하면 LLM 검수)로 잇습니다 — 커밋 ↔ 대화, 이슈 ↔ 대화,
  커밋 ↔ 이슈, 문서 ↔ 코드.
- **동일인 판단.** GitHub·Jira·Slack에서 표기가 다른 같은 사람을 하나의 Actor로 모읍니다.

노드·엣지 정의는 [docs/graph-schema.md](docs/graph-schema.md), 임베딩과 시맨틱 엣지는 [docs/embedding-design.md](docs/embedding-design.md),
질의 도구는 [docs/tools.md](docs/tools.md)에 있습니다.

## 아키텍처와 기술 스택

| 서비스 | 기술 | 역할 |
|--------|------|------|
| web-dashboard | React / Vite | 온보딩·소스 연결·그래프·채팅 화면과 랜딩. 배포에서는 nginx가 정적 파일과 API 프록시를 맡는다 |
| backend | Spring Boot (:8080) | 사용자·프로젝트·연동·대화 관리, OAuth 연동 9종, Slack `/why-code`, 결제(Paddle), MCP 서버와 그 인가 서버(OAuth 2.1) |
| pipeline-worker | Spring Boot (:8081) | 소스별 수집·웹훅 수신·정규화·RabbitMQ 발행. checkpoint 기반 증분 수집 |
| ai-engine | Python / FastAPI (:8000) | RabbitMQ consumer, Neo4j 그래프 구축, 변경 요약·임베딩, GraphRAG 질의 에이전트 |
| Neo4j | 그래프 DB | 지식 그래프(벡터 인덱스 포함) |
| PostgreSQL | RDB | backend와 pipeline-worker가 공유(Flyway) |
| RabbitMQ | 메시지 큐 | 수집 이벤트 전달, 재시도·DLQ |

LLM은 OpenAI를 씁니다(질의 gpt-5.4-mini, 변경 요약 gpt-4o-mini, 임베딩 text-embedding-3-large). 전체 데이터 흐름과 서비스별
규칙은 [CLAUDE.md](CLAUDE.md)와 각 서비스 디렉터리의 CLAUDE.md에 있습니다.

## 로컬에서 띄우기

```bash
cd infra/docker
cp .env.example .env        # OPENAI_API_KEY · BACKEND_CREDENTIAL_KEY · INTERNAL_SERVICE_TOKEN · GitHub App·Atlassian OAuth 값은 필수
./dev.sh up -d --build      # postgres · neo4j · rabbitmq · ai-engine · backend · pipeline-worker · web-dashboard
./dev.sh logs -f backend
./dev.sh down
```

대시보드는 http://localhost:5173, backend는 :8080, ai-engine은 :8000, pipeline-worker는 :8081입니다. 다른 소스(Slack·Discord·Notion 등)는
해당 OAuth 값을 `.env`에 채워야 연결됩니다. `docker compose`를 직접 치면 포트가 열리지 않으니 항상 `./dev.sh`를 씁니다
(배포는 `./prod.sh`, 절차는 [docs/deployment.md](docs/deployment.md)). 서비스별 빌드·테스트 명령은 각 서비스의 CLAUDE.md에 있습니다.

## 저장소 구조

```
services/backend          Spring Boot — 사용자·프로젝트·연동·대화·결제·MCP
services/pipeline-worker  Spring Boot — 수집·웹훅·정규화
services/ai-engine        Python — 그래프 구축·임베딩·질의 에이전트
clients/web-dashboard     React — 웹 프론트엔드와 랜딩
infra/docker              docker-compose(기본 + dev/prod 오버라이드), dev.sh · prod.sh
docs                      설계·계약·운영 문서 (색인: docs/README.md)
eval                      GraphRAG 품질 측정 — 골든 질문, 엣지 정답지, 러너, 결과
```

## 문서

문서의 현황(정본인지, 코드까지 갔는지, 열린 항목이 있는지)은 [docs/README.md](docs/README.md)가 관리합니다. 처음이라면
이 순서를 권합니다.

1. [docs/graph-schema.md](docs/graph-schema.md) — 그래프에 무엇이 어떻게 들어가는가
2. [docs/normalized-event.md](docs/normalized-event.md) — 수집기와 그래프 사이의 계약
3. [docs/measurement.md](docs/measurement.md) — 답의 품질을 숫자로 재는 방법

## 라이선스

이 저장소는 열람용으로 공개합니다. 별도 허가 없이 코드를 사용·복제·배포할 수 없습니다. 모든 권리는 저작권자에게 있습니다.
