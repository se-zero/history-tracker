"""tools/definitions.py 스키마 회귀 테스트 (오프라인 — Neo4j·OpenAI 불필요).

get_file_history의 limit 파라미터가 get_actor_activity와 같은 이유로 의도적으로
스키마에서 빠져 있는지 고정한다 — 있으면 LLM이 습관적으로 낮은 값을 넣어 관련도
재랭킹 전에 최신 N개로 이력을 잘라, 옛 관련 커밋 구제(2계층 반환의 목적)를 무력화한다.
"""

import unittest

from tools.definitions import TOOLS


def _tool(name: str) -> dict:
    return next(t for t in TOOLS if t["function"]["name"] == name)


class GetFileHistorySchemaTest(unittest.TestCase):
    def test_limit_property_is_absent(self):
        properties = _tool("get_file_history")["function"]["parameters"]["properties"]
        self.assertNotIn("limit", properties)


if __name__ == "__main__":
    unittest.main()
