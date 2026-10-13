"""Pytest bootstrap shared across the ai-engine test suite.

Several modules construct an OpenAI client at import time from
``os.environ["OPENAI_API_KEY"]`` (e.g. ``graph/embedder.py``,
``graph/actor_llm.py``, ``agent/orchestrator.py``). Provide a dummy key so the
offline unit suite can import these modules without a real credential.
``setdefault`` leaves any real key untouched for integration runs.
"""

import os

os.environ.setdefault("OPENAI_API_KEY", "sk-test-dummy-key-not-used")


import pytest


@pytest.fixture(autouse=True)
def _utterance_route_defaults_to_graph(monkeypatch):
    """발화 판별은 별도 LLM 호출이다. 단위 테스트가 네트워크를 타지 않게 기본은 그래프 경로·한국어로 둔다.

    언어를 ko로 정해 두는 이유: run()을 부르는 기존 테스트는 "current question" 같은 영어 자리표시 질문을 써서,
    판별이 언어를 정하지 않으면 대비책 코드 판정이 영어로 정해 한국어 경로를 보던 테스트가 영어 경로를 탄다.
    판별 동작은 test_conversational_intro·test_answer_language_path가 원래 함수를 다시 붙여 검증한다.
    """

    async def _graph(question, history=None, debug=None):
        return None, "ko"

    monkeypatch.setattr("agent.orchestrator._route_utterance", _graph)
