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
`QUERY_MODEL`(선택, 기본 `gpt-5.4-mini`), `GITHUB_REPO`/`GITHUB_TOKEN`(선택, 프로젝트 컨텍스트 pre-warm용).

수집 동시성(선택): `INGEST_MAX_CONCURRENCY`(기본 `4`), `INGEST_PREFETCH`(기본 = 동시성 값).
consumer는 project 단위로 파티셔닝해 project 내부는 직렬(순서·노드 경합·Actor race 보호), project 간은
`INGEST_MAX_CONCURRENCY`까지 동시 처리한다. OpenAI 호출은 rate_limiter가 페이싱하므로
동시성을 올려도 Tier 한도를 넘지 않는다(429·품질 저하 없음). 부하·환경에 따라 env로 조절한다.

수집 실패 안전망(재시도 → DLQ): consumer의 `handle()`이 실패한 이벤트를 조용히 버리지 않는다.
발행 측(pipeline-worker)은 이미 checkpoint를 넘긴 상태라 소비 실패는 재수집으로 복구되지 않기 때문이다.
실패하면 지연 재시도 큐 `history.events.retry`로 보내 `INGEST_RETRY_DELAY_MS`(기본 `180000`, 3분) 뒤 다시
소비하고, 최대 `INGEST_RETRY_MAX`(기본 `20`)회 재시도한다(총 재시도 창 ≈ 1시간). 소진되면 `history.events.dlq`에 보관(파킹)했다가,
장애 해소 후 admin `POST /dlq/replay`로 정상 파이프라인에 재투입한다(개수 조회는 `GET /dlq/stats`).
JSON 파싱 실패(malformed)는 재시도·replay가 무의미하므로 DLQ가 아닌 별도 inspect 큐
`history.events.parking`으로 보낸다(운영자 수동 확인용). 재발행은 소비 채널과 분리된 별도 채널에서 한다.

## 테스트

테스트는 두 계층으로 나뉜다.

### `tests/unit/` — 오프라인 단위 테스트 (기본 실행 대상)

Neo4j·OpenAI 없이 동작한다. `conftest.py`가 더미 `OPENAI_API_KEY`를 주입하고 LLM/그래프 호출은 모킹한다.
CI·리팩토링 안전망.

```bash
python -m pytest        # = tests/unit 만 실행 (pytest.ini의 testpaths)
```

갱신 의무가 붙은 안전망 2개만 여기서 설명한다.

- `test_import_surface.py` — 모든 1st-party 모듈이 import 되는지 + `graph.builder`/`tools.queries`/
  `agent.orchestrator`가 외부에서 쓰는 공개 심볼을 그대로 노출하는지 검증. **모듈 분해 리팩토링의 안전망.**
  모듈/공개 심볼을 추가·이동하면 이 파일의 목록도 함께 갱신한다.
- `test_api_routes.py` — `main.app`에 등록된 (method, path) 집합이 변하지 않았는지 검증. **HTTP 계약 가드.**
  라우트를 의도적으로 추가/변경/삭제할 때만 `EXPECTED_ROUTES`를 갱신한다.

그 외 도메인별 단위 테스트(멀티턴 히스토리, consumer 재시도, rate limiter 등)는 여기에 열거하지
않는다 — `tests/unit/`을 직접 본다.

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
  query.py           질의 — 공개 read API
  graph.py           그래프 조회·검색·서브그래프·삭제·빌드(202 비동기)·활동 조회
  admin.py           일회성 운영 트리거 (reference·migrations·slack 필터·issue-links·DLQ)
                     ※ 전체 라우트 계약의 단일 출처는 tests/unit/test_api_routes.py의 EXPECTED_ROUTES —
                       개별 경로를 여기에 열거하지 않는다

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
    schema.py            벡터/full-text 인덱스·유니크 제약 부트스트랩
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
  summarizer.py / path_filter.py / project_context.py / overview.py / search.py
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
