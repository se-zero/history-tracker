"""_common.cap_text/first_line 순수 함수 단위 테스트 — 본문 캡·첫 줄 헬퍼(B-1)의 경계값.

get_file_history에서 시작해 changeset.py·issue.py·files.py·actor.py가 공유하는 헬퍼다.
캡을 넘는 필드만 " …(생략)" 표시와 함께 잘라야 하고, 정확히 limit인 경우는 원문을 그대로
돌려줘야 검증기가 원문 대비 인용 매칭을 놓치지 않는다.
"""

import unittest

from tools.queries._common import cap_text, first_line


class CapTextTest(unittest.TestCase):
    def test_exactly_limit_untouched(self):
        text = "x" * 10
        self.assertEqual(cap_text(text, 10), text)

    def test_limit_plus_one_truncated_with_marker(self):
        text = "x" * 11
        out = cap_text(text, 10)
        self.assertEqual(out, "x" * 10 + " …(생략)")

    def test_none_passthrough(self):
        self.assertIsNone(cap_text(None, 10))

    def test_non_string_passthrough(self):
        # dict·list·숫자 등 비문자열은 그대로 — 호출부가 타입을 신경 쓰지 않게.
        self.assertEqual(cap_text(42, 10), 42)


class FirstLineTest(unittest.TestCase):
    def test_strips_surrounding_whitespace(self):
        self.assertEqual(first_line("  hello\nworld  "), "hello")

    def test_caps_first_line_length(self):
        self.assertEqual(first_line("abcdef\nrest", cap=3), "abc")

    def test_no_cap_returns_full_first_line(self):
        self.assertEqual(first_line("first line\nsecond"), "first line")

    def test_empty_after_strip_is_none(self):
        self.assertIsNone(first_line("   \n  "))

    def test_none_input_is_none(self):
        self.assertIsNone(first_line(None))


if __name__ == "__main__":
    unittest.main()
