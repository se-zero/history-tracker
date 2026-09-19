"""근거 회수 품질 개선 묶음 C — 시스템 프롬프트가 코드와 어긋나던 곳을 바로잡았는지 검증.

프롬프트 문자열을 검증기(_drop_unverified_quotes)나 쿼리 코드와 직접 실행해 비교할 수는
없으므로, orchestrator._SYSTEM_PROMPT 렌더 결과에 기대 문구가 있는지/없는지로 확인한다
(패턴은 test_internal_term_leak.py의 test_system_prompt_* 방식과 동일).

단언은 줄바꿈·들여쓰기를 공백 하나로 접은 사본에 대해 한다. 원문 그대로 단언하면 프롬프트
문단을 다시 감싸기만 해도 뜻과 무관하게 깨진다(묶음 C 리뷰에서 줄바꿈 한 곳 변경으로 실증).
"""

import os
import re
import unittest

os.environ.setdefault("OPENAI_API_KEY", "test-key")

from agent import orchestrator


def _flat(text: str) -> str:
    return re.sub(r"\s+", " ", text)


PROMPT = _flat(orchestrator._SYSTEM_PROMPT)
QUOTE_DESCRIPTION = _flat(
    orchestrator._GROUNDED_ANSWER_SCHEMA["schema"]["properties"]["evidence"]["items"]
    ["properties"]["quote"]["description"]
)


class QuoteFieldWordingTest(unittest.TestCase):
    """C-1: "제목+본문" 인용은 검증기가 항상 거부한다 — "한 필드"로 요구를 낮춘다."""

    def test_title_plus_body_wording_removed(self):
        self.assertNotIn("title+body", PROMPT)
        self.assertNotIn("title+body", QUOTE_DESCRIPTION)

    def test_evidence_rule_requires_single_field(self):
        self.assertIn(
            "pr title 또는 body 중 한 필드 / issue title 또는 body 중 한 필드 / message body", PROMPT
        )

    def test_evidence_rule_explains_why(self):
        self.assertIn("제목과 본문은 도구 결과에서 별개 필드라 이어 붙이면 원문 검증에서 삭제됩니다", PROMPT)

    def test_schema_quote_description_requires_single_field(self):
        # _GROUNDED_ANSWER_SCHEMA는 _SYSTEM_PROMPT와 별개 dict라 따로 확인한다.
        self.assertIn("pr title 또는 body 중 한 필드 / issue title 또는 body 중 한 필드", QUOTE_DESCRIPTION)


class ConfidencePolicyWordingTest(unittest.TestCase):
    """C-2: __MIN_CONF__ 미만 차단은 일부 연결에만 적용된다 — 도구별 실측과 같아야 한다."""

    def test_blanket_query_side_block_claim_removed(self):
        self.assertNotIn("쿼리 단에서 이미 차단되어 도구 결과에 없습니다", PROMPT)

    def test_filtered_links_include_document_tool_commit_links(self):
        # get_document_context의 커밋→문서 연결도 min_conf로 거른다(tools/queries/document.py).
        self.assertIn(
            "커밋→이슈(TRIGGERED_BY), 이슈→문서(DESCRIBED_IN), "
            "그리고 get_document_context 결과의 커밋→문서 연결입니다",
            PROMPT,
        )
        self.assertNotIn("(DESCRIBED_IN)뿐입니다", PROMPT)

    def test_unfiltered_links_listed(self):
        self.assertIn("커밋↔대화, 커밋·PR 결과의 문서 연결, 이슈↔대화는 차단되지 않으므로", PROMPT)

    def test_link_source_values_explained(self):
        self.assertIn("link_source가 'text'면 본문에 명시된 참조, 'semantic'이면 유사도로 추론한 연결입니다", PROMPT)

    def test_propagated_links_flagged_as_possibly_inferred(self):
        # 전파 엣지는 confidence가 없어, 빈 confidence를 "명시 참조"와 한 묶음으로 설명하면
        # 시맨틱 추정에서 퍼진 연결까지 확정으로 읽힌다(graph/maintenance.py).
        self.assertIn(
            "'propagated'(이슈↔대화에만 있음)는 같은 스레드의 다른 메시지에 걸린 연결을 옮겨 온 것이라 "
            "원래 연결이 추정이었을 수 있으니 확정 사실로 단정하지 마세요",
            PROMPT,
        )
        self.assertNotIn("confidence가 비어 있으면 명시 참조이거나", PROMPT)


class NestedIssueQuoteTimestampRuleTest(unittest.TestCase):
    """C-3: 도구 결과의 이슈 항목 인용은 created_at/closed_at을 event_meaning에 맞춰 써야 한다."""

    def test_created_and_closed_at_mapped_to_event_meaning(self):
        self.assertIn("created_at(→ event_meaning=issue_created) 또는 closed_at(→ issue_closed)", PROMPT)

    def test_locations_enumerated(self):
        self.assertIn(
            "도구 결과에 실린 이슈 항목(get_changeset_context·get_pr_context의 issues[*], "
            "get_conflict_context의 issue_contexts[*], get_file_history의 detail[*].issues[*], "
            "get_issue_context의 루트 자체와 descendants[*])",
            PROMPT,
        )

    def test_fallback_to_get_issue_context_when_both_missing(self):
        self.assertIn("둘 다 없으면 시각을 지어내지 말고 get_issue_context로 그 이슈를 조회한 뒤 인용하세요", PROMPT)


class TimelineActorCandidateWordingTest(unittest.TestCase):
    """C-4: get_timeline actor 스코프의 candidates도 원인·재호출 방법을 안내해야 한다."""

    def test_actor_scope_listed_as_ambiguity_cause(self):
        self.assertIn("actor 스코프는 표시 이름이 여러 사람에 걸침", PROMPT)

    def test_alias_recall_guidance_present(self):
        self.assertIn(
            "actor 스코프의 candidates는 이름 또는 alias로 재호출한다 — "
            "표시 이름이 서로 같으면 alias (예: GITHUB:se-zero)가 유일한 구분자다",
            PROMPT,
        )


if __name__ == "__main__":
    unittest.main()
