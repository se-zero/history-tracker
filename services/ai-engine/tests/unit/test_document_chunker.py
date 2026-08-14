"""DocumentSection 청킹 규칙 단위 테스트."""

import unittest

from graph.document_chunker import MAX_SECTION_CHARS, chunk_document


class DocumentChunkerTest(unittest.TestCase):
    def test_preserves_heading_hierarchy_and_intro_context(self):
        intro = "서두 설명 " * 40
        auth = "인증 설계 설명 " * 40
        token = "토큰 갱신 설명 " * 40

        sections = chunk_document(
            "백엔드 설계서",
            f"{intro}\n\n# 인증\n{auth}\n\n## 토큰 갱신\n{token}",
        )

        self.assertEqual(
            [section.heading_path for section in sections],
            ["백엔드 설계서", "인증", "인증 > 토큰 갱신"],
        )
        self.assertEqual(sections[1].text, auth.strip())

    def test_splits_oversized_paragraph_without_losing_text(self):
        body = "가" * (MAX_SECTION_CHARS + 321)

        sections = chunk_document("긴 문서", body)

        self.assertGreater(len(sections), 1)
        self.assertTrue(all(len(section.text) <= MAX_SECTION_CHARS for section in sections))
        self.assertEqual("".join(section.text for section in sections), body)

    def test_merges_short_heading_into_following_section(self):
        detail = "상세 설명 " * 50

        sections = chunk_document("문서", f"# 요약\n짧음\n\n# 세부\n{detail}")

        self.assertEqual(len(sections), 1)
        self.assertEqual(sections[0].heading_path, "세부")
        self.assertTrue(sections[0].text.startswith("요약\n짧음"))

    def test_headingless_document_uses_document_title(self):
        sections = chunk_document("제목", "내용 " * 80)

        self.assertEqual([section.heading_path for section in sections], ["제목"])


if __name__ == "__main__":
    unittest.main()
