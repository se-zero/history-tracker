# CLAUDE.md — ai-engine

## 역할

Python/FastAPI(:8000) GraphRAG 서비스. 두 가지 일을 한다.

1. **수집 consumer**: RabbitMQ(`history.events`)에서 `NormalizedEvent`를 받아 Neo4j 지식 그래프로 구축하고,
   수집이 유휴해지면 소스 간 시맨틱 엣지(Layer 4)를 빌드한다.
2. **질의 API**: `/query`·`/graph/*` HTTP로 자연어 질문에 tool-calling 에이전트(OpenAI)가 답한다.

전체 아키텍처·데이터 흐름은 루트 [CLAUDE.md](../../CLAUDE.md) 참고.

## 실행

전체 스택은 `infra/docker`의 docker-compose로 띄운다 (루트 CLAUDE.md 참고). 로컬 단독 실행:

```bash
cd services/ai-engine
python -m venv .venv && source .venv/bin/activate        # 최초 1회
pip install -r requirements.txt -r requirements-dev.txt  # dev = pytest
uvicorn main:app --reload --port 8000
```

필요 환경변수: `OPENAI_API_KEY`(필수), `NEO4J_URI`/`NEO4J_USER`/`NEO4J_PASSWORD`, `RABBITMQ_URL`,
`QUERY_MODEL`(선택, 기본 `gpt-4o-mini`), `GITHUB_REPO`/`GITHUB_TOKEN`(선택, 프로젝트 컨텍스트 pre-warm용).

## 테스트

테스트는 두 계층으로 나뉜다.

### `tests/unit/` — 오프라인 단위 테스트 (기본 실행 대상)

Neo4j·OpenAI 없이 동작한다. `conftest.py`가 더미 `OPENAI_API_KEY`를 주입하고 LLM/그래프 호출은 모킹한다.
CI·리팩토링 안전망.

```bash
python -m pytest        # = tests/unit 만 실행 (pytest.ini의 testpaths)
```

- `test_import_surface.py` — 모든 1st-party 모듈이 import 되는지 + `graph.builder`/`tools.queries`/
  `agent.orchestrator`가 외부에서 쓰는 공개 심볼을 그대로 노출하는지 검증. **모듈 분해 리팩토링의 안전망.**
  모듈/공개 심볼을 추가·이동하면 이 파일의 목록도 함께 갱신한다.
- `test_api_routes.py` — `main.app`에 등록된 (method, path) 집합이 변하지 않았는지 검증. **HTTP 계약 가드.**
  라우트를 의도적으로 추가/변경/삭제할 때만 `EXPECTED_ROUTES`를 갱신한다.
- `test_multiturn_history.py` — 멀티턴 히스토리/요약 동작 단위 테스트 (LLM 모킹).

### `tests/integration/` — 실인프라 필요 (기본 실행 제외)

라이브 Neo4j 및/또는 실제 OpenAI 키가 있어야 한다. `pytest.ini`의 `testpaths`가 `tests/unit`이라
기본 `pytest`에는 수집되지 않는다. 현재는 스크립트 형태로, 서비스 루트를 import 경로에 올려 직접 실행한다.

```bash
export OPENAI_API_KEY=sk-...
PYTHONPATH=. python tests/integration/test_phase1_regression.py
```

## 패키지 구조

```
main.py            FastAPI 앱 부트스트랩 — lifespan(Neo4j 초기화·consumer 기동) + /health + 라우터 include
openai_client.py   공유 OpenAI 클라이언트 (lazy lru_cache 팩토리)
query_models.py    요청/응답 Pydantic 모델 (QueryRequest, SummaryRequest)
conftest.py        pytest 부트스트랩 (OPENAI_API_KEY shim)

routers/           HTTP 엔드포인트 (APIRouter, prefix 없이 전체 경로 명시)
  query.py           /query, /query/summary            — 공개 read API
  graph.py           /graph/overview, /graph/projects/{id}, /graph/build(202·비동기), /graph/build/status
  admin.py           /reference/*, /migrations/*, /slack/filter, /issue-links/build, /test/ingest — 일회성 운영 트리거

agent/
  orchestrator.py    GraphRAG tool-calling 에이전트 루프 + 답변 Structured Output

tools/             LLM tool-calling
  definitions.py     도구 스키마 (OpenAI function 정의)
  executor.py        도구 디스패치 → queries 호출
  queries/           도메인별 Neo4j 읽기 쿼리 (패키지)
    __init__.py        facade — 13개 공개 쿼리 함수 re-export
    _common.py         공용 드라이버/상수/헬퍼 (_MIN_CONFIDENCE, _group_communications_by_thread)
    issue.py changeset.py actor.py files.py discovery.py

graph/             Neo4j 그래프 구축 + 수집
  consumer.py        RabbitMQ consumer
  event_handler.py   NormalizedEvent → 그래프 쓰기 (수집 진입점)
  postprocess.py     per-project 후처리(Layer 4) 빌드 + 디바운스. 빌드는 프로젝트 단위 비동기
                     (POST /graph/build는 202 후 백그라운드 태스크, GET /graph/build/status 폴링).
                     같은 프로젝트는 coalesce, 다른 프로젝트는 _build_semaphore(MAX_CONCURRENCY)로 제한.
                     상태/dirty는 in-process — 수평 확장 시 공유 저장소로 교체 필요
  builder.py         facade — 아래 분해 모듈의 공개 심볼 re-export (하위 호환)
    driver.py            드라이버 수명주기 (get_driver/close_driver)
    schema.py            벡터 인덱스·유니크 제약 부트스트랩
    writes.py            NormalizedEvent 단위 upsert/link (수집 쓰기 경로)
    maintenance.py       백필·마이그레이션·정리·프로젝트 삭제
    reference_store.py   REFERENCE 엣지용 ReferenceStore 어댑터
    issue_link_store.py  시맨틱 이슈 링크용 IssueLinkStore 어댑터
    actor_store.py       Actor 동일인 판단용 ActorStore 어댑터
    communication_store.py  Slack 필터용 Communication 조회/정리
  embedder.py        임베딩 생성
  reference_builder.py / issue_linker.py / issue_verifier.py   시맨틱 링크 빌더(방안 A/D)
  actor_resolver.py / actor_llm.py                             Actor 동일인 판단
  slack_filter.py / slack_llm_filter.py / slack_batch_filter.py  Slack 노이즈 필터
  summarizer.py / path_filter.py / project_context.py / overview.py
```

## 코딩 규칙

- **facade 패턴**: `graph/builder.py`와 `tools/queries/__init__.py`는 분해된 모듈의 공개 심볼을 re-export하는
  facade다. 호출부는 facade로 import해 하위 호환을 유지하되, **분해 모듈끼리는 정규 위치**(`graph.driver` 등)에서
  import한다 — facade를 경유하면 순환 import가 생긴다.
- **OpenAI 클라이언트는 `openai_client.get_openai_client()`만 사용**한다. 모듈 레벨에서 `OpenAI(...)`를 직접
  생성하지 않는다 (중복·import 시점 키 강제 방지).
- **chat/embedding 호출은 게이트웨이(`openai_client.chat_completion` / `openai_client.embed`)로** 한다.
  `client.chat.completions.create()`를 직접 호출하지 않는다 — 게이트웨이가 `rate_limiter`로 RPM·TPM을
  페이싱하고 우선순위(`Priority.INTERACTIVE` 질의 / `Priority.BACKGROUND` 수집·빌드)를 적용한다.
  한도는 env(`OPENAI_RPM_CHAT`/`OPENAI_TPM_CHAT`/`OPENAI_RPM_EMBED`/`OPENAI_TPM_EMBED`)로 외부화돼 있다.
- **import 시점 부작용 금지**: 모듈 최상단에서 네트워크 호출, 클라이언트 생성, `os.environ["X"]` 하드 subscript를
  하지 않는다 — 오프라인 import(테스트 포함)가 가능해야 한다. 설정은 함수 호출 시점에 lazy하게 읽는다.
- **HTTP 엔드포인트는 `routers/`에** 추가한다. `main.py`는 부트스트랩(lifespan·라우터 include)만 둔다.
  라우트를 추가/변경하면 `tests/unit/test_api_routes.py`의 `EXPECTED_ROUTES`를 갱신한다.
- 모듈을 추가/이동하면 `tests/unit/test_import_surface.py`의 `MODULES`에 등록한다.
- **모든 그래프 노드·쿼리는 `project_id`로 스코프**한다 (프로젝트 격리 — 자연키가 프로젝트 간 충돌하므로
  `project_id` 없는 MERGE/MATCH는 데이터 누출 위험). 인가는 backend가 담당하고 ai-engine은 내부 서비스로 신뢰한다.
- 주석·docstring은 한국어로 작성한다 (코드베이스 관행).
```
