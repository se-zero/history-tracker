# ai-engine tests

테스트는 두 계층으로 나뉜다.

## `tests/unit/` — 오프라인 단위 테스트 (기본 실행 대상)

Neo4j·OpenAI 없이 동작한다. `conftest.py`가 더미 `OPENAI_API_KEY`를 주입하고,
LLM/그래프 호출은 모킹한다. CI·리팩토링 안전망으로 쓴다.

```bash
cd services/ai-engine
pip install -r requirements.txt -r requirements-dev.txt   # 최초 1회
python -m pytest                                          # = tests/unit 만 실행
```

- `test_import_surface.py` — 모든 1st-party 모듈이 import 되는지, 그리고
  `graph.builder` / `tools.queries` / `agent.orchestrator`가 외부에서 import 하는
  공개 심볼을 그대로 노출하는지 검증한다. **모듈 분해 리팩토링(Phase 1·2)의 안전망.**
  분해 후 facade가 재-export를 빠뜨리면 이 테스트가 빨개진다. 모듈/공개 심볼을
  추가·이동할 때 이 파일의 목록도 함께 갱신한다.
- `test_multiturn_history.py` — 멀티턴 히스토리/요약 동작 단위 테스트 (LLM 모킹).

## `tests/integration/` — 실인프라 필요 (기본 실행 제외)

라이브 Neo4j 및/또는 실제 OpenAI 키가 있어야 한다. `pytest.ini`의 `testpaths`가
`tests/unit`이라 기본 `pytest`에는 수집되지 않는다. 현재는 스크립트 형태로,
서비스 루트를 import 경로에 올려 직접 실행한다.

```bash
cd services/ai-engine
export OPENAI_API_KEY=sk-...
PYTHONPATH=. python tests/integration/test_phase1_regression.py
```

> 이 스크립트들을 pytest 케이스(`-m integration`로 opt-in)로 전환하는 것은 후속 작업이다.
> 안전망을 만드는 단계(Phase 0)에서 동작을 바꾸는 위험을 피하려고 일부러 미뤘다.
