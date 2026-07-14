"""executor 결과 상한 처리(_truncate_payload) 단위 테스트 — Neo4j 없이 실행.

문자열 중간 컷은 JSON을 파손시키고 뒤쪽 행의 증발을 숨긴다(case-27에서 파일 이력의
오래된 커밋이 조용히 누락). list 결과는 행 단위로 줄여 JSON 유효성과 잘림 사실 고지를
보장해야 한다.
"""

import json
import unittest

from tools.executor import _MAX_RESULT_CHARS, _truncate_payload


def _long_rows(n: int, row_chars: int = 500) -> list[dict]:
    return [{"hash": f"{i:07d}", "diff_summary": "x" * row_chars} for i in range(n)]


class TruncatePayloadTest(unittest.TestCase):
    def test_list_truncates_by_rows_and_stays_valid_json(self):
        rows = _long_rows(40)
        payload = json.dumps(rows, ensure_ascii=False)
        self.assertGreater(len(payload), _MAX_RESULT_CHARS)

        out = _truncate_payload(rows, payload)

        self.assertLessEqual(len(out), _MAX_RESULT_CHARS)
        parsed = json.loads(out)  # 파싱 실패하면 문자열 컷이 일어난 것
        self.assertIn("_truncated", parsed[-1])
        self.assertEqual(parsed[0]["hash"], rows[0]["hash"])  # 앞쪽 행 보존
        self.assertIn("생략", parsed[-1]["_truncated"])

    def test_marker_reports_total_and_omitted_counts(self):
        rows = _long_rows(40)
        out = _truncate_payload(rows, json.dumps(rows, ensure_ascii=False))
        marker = json.loads(out)[-1]["_truncated"]
        kept = len(json.loads(out)) - 1
        self.assertIn(f"전체 40건 중 앞 {kept}건", marker)
        self.assertIn(f"{40 - kept}건 생략", marker)

    def test_dict_falls_back_to_string_cut_with_notice(self):
        result = {"big": "y" * (2 * _MAX_RESULT_CHARS)}
        payload = json.dumps(result, ensure_ascii=False)

        out = _truncate_payload(result, payload)

        self.assertIn("잘렸습니다", out)
        self.assertTrue(out.startswith(payload[:100]))
        # limit 축소를 조언하던 역효과 문구가 사라졌는지
        self.assertNotIn("limit을 줄이거나", out)

    def test_single_huge_row_falls_back_to_string_cut(self):
        rows = [{"big": "z" * (2 * _MAX_RESULT_CHARS)}, {"small": 1}]
        out = _truncate_payload(rows, json.dumps(rows, ensure_ascii=False))
        self.assertIn("잘렸습니다", out)


if __name__ == "__main__":
    unittest.main()
