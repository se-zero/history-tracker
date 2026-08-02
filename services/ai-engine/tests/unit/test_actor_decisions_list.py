"""Actor 결정 이력 조회 단위 테스트 (오프라인 — Neo4j driver fake 주입).

docs/actor-manual-merge.md 감사 원칙: list_decisions의 aliases_a/aliases_b는 source_id(계정ID)가
아니라 조회 시점에 ActorAlias에서 읽은 {source, name, erased}로 내려가야 한다 — 목록에 계정ID를
깔지 않는 원칙과, 결정 노드 자체에는 이름을 저장하지 않는(개인정보 사본 금지) 설계를 코드 계약으로
고정한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_admin import list_decisions


class _FakeDataResult:
    def __init__(self, rows):
        self._rows = rows

    async def data(self):
        return self._rows


class _FakeSession:
    """호출 순서대로 canned 결과를 돌려주는 fake 세션.

    list_decisions는 같은 세션 안에서 결정 조회 → alias 조회 순으로 session.run을
    정확히 2번 호출하므로, 응답을 호출 순서에 맞춰 큐로 소비한다.
    """

    def __init__(self, responses: list[list[dict]]):
        self._responses = list(responses)
        self.calls: list[tuple[str, dict]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        rows = self._responses.pop(0)
        return _FakeDataResult(rows)


class _FakeDriver:
    def __init__(self, responses: list[list[dict]]):
        self.session_obj = _FakeSession(responses)

    def session(self):
        return self.session_obj


class ListDecisions(unittest.TestCase):
    def test_converts_source_ids_to_source_name_erased_and_drops_raw_id(self):
        decisions = [
            {
                "decision_id": "d1", "kind": "same",
                "aliases_a": ["GITHUB:more"], "aliases_b": ["SLACK:less"],
                "canonical_uuid": "more-uuid", "merged_uuid": "less-uuid",
                "note": "", "decided_at": "2026-01-01T00:00:00Z",
            }
        ]
        aliases = [
            {"source_id": "GITHUB:more", "source": "GITHUB", "name": "More Actor", "erased": None},
            {"source_id": "SLACK:less", "source": "SLACK", "name": "Less Actor", "erased": None},
        ]
        driver = _FakeDriver([decisions, aliases])

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(list_decisions("p1"))

        self.assertEqual(len(result), 1)
        decision = result[0]
        self.assertEqual(
            decision["aliases_a"], [{"source": "GITHUB", "name": "More Actor", "erased": None}]
        )
        self.assertEqual(
            decision["aliases_b"], [{"source": "SLACK", "name": "Less Actor", "erased": None}]
        )
        for group in (decision["aliases_a"], decision["aliases_b"]):
            for alias in group:
                self.assertNotIn("source_id", alias)
        response_text = str(result)
        self.assertNotIn("GITHUB:more", response_text)
        self.assertNotIn("SLACK:less", response_text)

    def test_missing_alias_match_falls_back_to_source_prefix(self):
        """소스 연동 해제로 alias가 삭제된 뒤에도 반대편이 남아 결정이 보존되는 경우 —
        name/erased는 채울 게 없지만 source_id 접두사에서 소스명만 유도해 빈 칩을 피한다."""
        decisions = [
            {
                "decision_id": "d1", "kind": "distinct",
                "aliases_a": ["GITHUB:gone"], "aliases_b": [],
                "canonical_uuid": None, "merged_uuid": None,
                "note": "", "decided_at": "2026-01-01T00:00:00Z",
            }
        ]
        driver = _FakeDriver([decisions, []])

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(list_decisions("p1"))

        self.assertEqual(
            result[0]["aliases_a"], [{"source": "GITHUB", "name": None, "erased": None}]
        )


if __name__ == "__main__":
    unittest.main()
