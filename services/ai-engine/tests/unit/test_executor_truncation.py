"""executor 결과 상한 처리(_truncate_payload) 단위 테스트 — Neo4j 없이 실행.

문자열 중간 컷은 JSON을 파손시키고 뒤쪽 행의 증발을 숨긴다(case-27에서 파일 이력의
오래된 커밋이 조용히 누락). list 결과는 행 단위로 줄여 JSON 유효성과 잘림 사실 고지를
보장해야 한다.
"""

import json
import unittest

from tools.executor import _MAX_RESULT_CHARS, _round_scores, _truncate_payload


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

    def test_file_history_dict_trims_context_and_keeps_detail(self):
        # get_file_history 2계층 dict — 상한 초과 시 개요(context) stub부터 줄이고
        # detail(인용 대상)은 보존, JSON 유효성과 축약 고지를 유지해야 한다.
        result = {
            "path": "src/x.py",
            "detail": [{"hash": "d0", "message": "m", "diff_summary": "s"}],
            "context": [{"hash": f"{i:04d}", "title": "t" * 80} for i in range(400)],
        }
        payload = json.dumps(result, ensure_ascii=False)
        self.assertGreater(len(payload), _MAX_RESULT_CHARS)

        out = _truncate_payload(result, payload)

        self.assertLessEqual(len(out), _MAX_RESULT_CHARS)
        parsed = json.loads(out)  # 파싱 실패하면 문자열 컷이 일어난 것
        self.assertEqual(parsed["detail"], result["detail"])   # 인용 대상 보존
        self.assertLess(len(parsed["context"]), 400)           # 개요는 줄어듦
        self.assertIn("context_truncated", parsed)
        self.assertIn("축약", parsed["context_truncated"])

    def test_file_history_dict_trims_detail_when_detail_alone_exceeds(self):
        # detail(인용 대상)만으로 상한을 넘겨도 문자열 컷(JSON 파손) 대신 행 단위로 줄이고
        # 최소 1건은 남긴다 — 오래된 커밋 증발을 JSON 파손으로 숨기던 case-27 재발 방지.
        result = {
            "path": "src/x.py",
            "detail": [{"hash": f"{i:02d}", "message": "m" * 900, "diff_summary": "d" * 300}
                       for i in range(8)],
            "context": [],
        }
        payload = json.dumps(result, ensure_ascii=False)
        self.assertGreater(len(payload), _MAX_RESULT_CHARS)

        out = _truncate_payload(result, payload)

        self.assertLessEqual(len(out), _MAX_RESULT_CHARS)
        parsed = json.loads(out)  # 파싱 실패하면 JSON이 깨진 것
        self.assertGreaterEqual(len(parsed["detail"]), 1)      # 최소 1건 보존
        self.assertLess(len(parsed["detail"]), 8)              # 일부는 줄어듦
        self.assertIn("detail_truncated", parsed)


class TrimGenericDictTest(unittest.TestCase):
    """get_pr_context·get_issue_context 등 tiered(detail/context)도 timeline(events)도 아닌
    일반 dict의 트리머(_trim_generic_dict, B-2) — 문자열 컷(JSON 파손) 대신 최상위 리스트
    필드를 행 단위로 줄인다. 실측(2026-08-21): get_pr_context 49회 중 37회가 문자열 컷에
    걸렸고 절단 지점이 file_changes·body·descendants 안쪽이었다.
    """

    def test_multiple_lists_and_long_body_stay_valid_json_with_body_intact(self):
        result = {
            "pr_number": 42,
            "body": "B" * 500,  # 인용 원문 — 잘리면 인용 검증이 깨진다
            "changesets": [{"hash": f"{i:07d}aaa", "message": "m" * 300} for i in range(20)],
            "issues": [{"issue_key": f"HT-{i}", "title": "t" * 50} for i in range(20)],
        }
        payload = json.dumps(result, ensure_ascii=False)
        self.assertGreater(len(payload), _MAX_RESULT_CHARS)

        out = _truncate_payload(result, payload)
        parsed = json.loads(out)  # 파싱 실패하면 문자열 컷이 일어난 것

        self.assertLessEqual(len(out), _MAX_RESULT_CHARS)
        self.assertEqual(parsed["body"], result["body"])  # 인용 원문 보존
        self.assertEqual(parsed["changesets"][0], result["changesets"][0])  # 앞쪽 행 보존

    def test_largest_list_shrinks_first(self):
        result = {
            "small_list": [{"hash": f"s{i}", "x": "y"} for i in range(3)],
            "big_list": [{"hash": f"{i:07d}", "message": "m" * 600} for i in range(20)],
        }
        payload = json.dumps(result, ensure_ascii=False)
        self.assertGreater(len(payload), _MAX_RESULT_CHARS)

        out = _truncate_payload(result, payload)
        parsed = json.loads(out)

        self.assertLessEqual(len(out), _MAX_RESULT_CHARS)
        self.assertEqual(len(parsed["small_list"]), 3)     # 작은 리스트는 건드리지 않음
        self.assertLess(len(parsed["big_list"]), 20)        # 큰 리스트만 줄어듦
        self.assertIn("big_list_truncated", parsed)
        self.assertNotIn("small_list_truncated", parsed)

    def test_truncation_notice_lists_identifiers_hash_and_pr_number(self):
        result = {
            "commits": [{"hash": f"{i:07d}full", "message": "m" * 700} for i in range(15)],
            "pulls": [{"pr_number": i, "title": "t" * 700} for i in range(15)],
        }
        payload = json.dumps(result, ensure_ascii=False)
        self.assertGreater(len(payload), _MAX_RESULT_CHARS)

        out = _truncate_payload(result, payload)
        parsed = json.loads(out)

        self.assertIn("commits_truncated", parsed)
        self.assertIn("pulls_truncated", parsed)
        # hash 식별자는 앞 7자만
        self.assertIn(result["commits"][-1]["hash"][:7], parsed["commits_truncated"])
        self.assertNotIn(result["commits"][-1]["hash"], parsed["commits_truncated"])  # 전체 hash는 없음
        # pr_number는 #N 형태
        self.assertIn(f"#{result['pulls'][-1]['pr_number']}", parsed["pulls_truncated"])

    def test_more_than_twenty_omitted_ids_are_summarized(self):
        result = {
            "commits": [{"hash": f"{i:07d}full", "message": "m" * 100} for i in range(80)],
        }
        payload = json.dumps(result, ensure_ascii=False)
        self.assertGreater(len(payload), _MAX_RESULT_CHARS)

        out = _truncate_payload(result, payload)
        parsed = json.loads(out)

        omitted = 80 - len(parsed["commits"])
        self.assertGreater(omitted, 20)  # 시나리오 전제 — 20건 넘게 생략돼야 이 테스트가 의미 있다
        self.assertIn(f" 외 {omitted - 20}건", parsed["commits_truncated"])

    def test_no_list_fields_still_falls_back_to_string_cut(self):
        # 기존 test_dict_falls_back_to_string_cut_with_notice와 같은 전제 — 리스트가 없으면
        # 일반 트리머가 줄일 게 없어 None을 반환하고 문자열 컷으로 떨어진다.
        result = {"big": "y" * (2 * _MAX_RESULT_CHARS)}
        out = _truncate_payload(result, json.dumps(result, ensure_ascii=False))
        self.assertIn("잘렸습니다", out)

    def test_tiered_dict_does_not_use_generic_notice_format(self):
        # detail/context 계층 dict는 여전히 _trim_tiered_dict가 처리한다 — 일반 트리머의
        # "표시 — 생략:" 문구가 아니라 "축약" 문구가 나와야 한다.
        result = {
            "path": "src/x.py",
            "detail": [{"hash": "d0", "message": "m", "diff_summary": "s"}],
            "context": [{"hash": f"{i:04d}", "title": "t" * 80} for i in range(400)],
        }
        payload = json.dumps(result, ensure_ascii=False)
        out = _truncate_payload(result, payload)
        parsed = json.loads(out)
        self.assertIn("축약", parsed["context_truncated"])

    def test_tiered_dict_that_cannot_fit_falls_back_to_string_cut_not_generic(self):
        # detail 한 행만으로 상한을 넘으면 _trim_tiered_dict가 None을 돌려준다("detail 최소
        # 1건" 보호). 그때 일반 트리머로 넘어가면 detail을 비워 인용 대상이 조용히 사라진다 —
        # executor의 is_tiered 조건이 그걸 막아 문자열 컷으로 떨어져야 한다.
        result = {
            "path": "src/x.py",
            "detail": [{"hash": "d" * 40, "message": "m" * 9000, "diff_summary": "s"}],
            "context": [],
        }
        payload = json.dumps(result, ensure_ascii=False)
        out = _truncate_payload(result, payload)
        self.assertIn("잘렸습니다", out)            # 문자열 컷으로 떨어졌다
        self.assertNotIn("detail_truncated", out)   # 일반 트리머가 detail을 비우지 않았다
        self.assertTrue(out.startswith(payload[:100]))

    def test_conflict_context_rows_use_generic_id_as_identifier(self):
        # get_conflict_context는 이슈·PR·문서 행의 식별자를 범용 키 `id`에 담는다 — 폴백이
        # 없으면 생략 고지가 개수만 알려 드릴다운할 수 없다.
        result = {
            "hash": "a" * 40, "commit_message": "m",
            "issue_contexts": [{"source": "JIRA", "id": f"HT-{i}", "text": "t" * 400}
                               for i in range(25)],
            "comm_contexts": [], "pr_contexts": [], "doc_contexts": [], "file_changes": [],
        }
        payload = json.dumps(result, ensure_ascii=False)
        out = json.loads(_truncate_payload(result, payload))
        notice = out["issue_contexts_truncated"]
        self.assertIn("생략: ", notice)
        self.assertIn("HT-24", notice)   # 맨 뒤라 가장 먼저 빠진 행의 id

    def test_timeline_events_dict_does_not_use_generic_notice_format(self):
        # events dict는 여전히 _trim_timeline_dict가 처리한다 — window.covered_to 갱신과
        # "truncated" 필드가 나와야 하고, 일반 트리머의 "<field>_truncated" 형식이 아니어야 한다.
        events = [
            {"type": "ChangeSet", "occurredAt": f"2026-01-{(i % 28) + 1:02d}T00:00:00Z",
             "data": {"hash": f"{i:07d}", "message": "m" * 200}}
            for i in range(60)
        ]
        result = {"scope": {"type": "project"}, "window": {}, "total_events": len(events), "events": events}
        payload = json.dumps(result, ensure_ascii=False)
        self.assertGreater(len(payload), _MAX_RESULT_CHARS)

        out = _truncate_payload(result, payload)
        parsed = json.loads(out)

        self.assertIn("truncated", parsed)
        self.assertNotIn("events_truncated", parsed)
        self.assertIn("covered_to", parsed["window"])


class RoundScoresTest(unittest.TestCase):
    """점수·신뢰도 반올림 — 원시 float가 답변 본문에 그대로 실리는 것을 막는다.

    실측(2026-08-21 eval): "연결 신뢰도가 0.6319704674079566인 유사도 기반 추정 연결".
    판단에는 두 자리면 충분하다 — 프롬프트의 "0.5~0.7 구간이면 추정으로 명시" 규칙도 그대로 성립한다.
    """

    def test_rounds_nested_scores(self):
        result = {
            "issues": [{"issue_key": "HT-1", "confidence": 0.6319704674079566}],
            "documents": [{"relevance": 0.123456789, "section": "설계"}],
        }

        out = _round_scores(result)

        self.assertEqual(0.63, out["issues"][0]["confidence"])
        self.assertEqual(0.12, out["documents"][0]["relevance"])
        self.assertEqual("설계", out["documents"][0]["section"])

    def test_non_score_numbers_untouched(self):
        # 기간·개수는 반올림 대상이 아니다 — duration_days 50.8이 50.8로 남아야 한다.
        result = {"duration_days": 50.83333, "discussion_count": 13, "hash": "8cdb0cc"}

        out = _round_scores(result)

        self.assertEqual(50.83333, out["duration_days"])
        self.assertEqual(13, out["discussion_count"])
        self.assertEqual("8cdb0cc", out["hash"])

    def test_none_and_lists_survive(self):
        result = [{"confidence": None}, {"confidence": 1.0}]

        out = _round_scores(result)

        self.assertIsNone(out[0]["confidence"])
        self.assertEqual(1.0, out[1]["confidence"])


if __name__ == "__main__":
    unittest.main()
