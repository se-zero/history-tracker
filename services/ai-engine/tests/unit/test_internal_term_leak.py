"""내부 용어 노출 가드(_sanitize_internal_terms)와 용어집(agent.glossary) 단위 테스트.

답변 본문에 도구 결과의 필드명이 그대로 실려 사용자에게 노출되던 문제를 막는 가드다
(실측: "HT-129의 discussion_count가 12건입니다"). 프롬프트 규칙만으로는 확실히 막지 못해
서버가 summary·unknown_aspects를 사용자 표현으로 치환한다.

핵심 안전장치는 **키 게이트**다 — 토큰이 이번 턴 도구 결과에 JSON 키로 실제 존재할 때만
치환한다. 사용자 코드에 우연히 같은 이름의 심볼이 있어도(우리 필드가 아니면) 건드리지 않는다.

모킹·import 패턴은 tests/unit/test_direct_quote_detection.py를 그대로 따른다.
"""

import json
import os
import sys
import unittest
from types import ModuleType
from unittest.mock import AsyncMock

os.environ.setdefault("OPENAI_API_KEY", "test-key")

executor_module = ModuleType("tools.executor")
executor_module.execute = AsyncMock()
sys.modules.setdefault("tools.executor", executor_module)

from agent import glossary, orchestrator


def _tool_messages(payload: dict) -> list[dict]:
    """도구 결과 메시지 1건짜리 haystack을 만든다."""
    return [{"role": "tool", "content": json.dumps(payload, ensure_ascii=False)}]


class SanitizeInternalTermsTest(unittest.TestCase):
    def test_field_name_in_summary_replaced_with_user_label(self):
        messages = _tool_messages({"issue_key": "HT-129", "discussion_count": 12})
        structured = {
            "summary": "HT-129의 discussion_count가 12건으로 가장 높습니다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._sanitize_internal_terms(structured, messages, 0, debug)

        self.assertEqual(
            "HT-129의 관련 대화 메시지 수가 12건으로 가장 높습니다.", structured["summary"]
        )
        self.assertNotIn("discussion_count", structured["summary"])
        self.assertEqual(["discussion_count"], [r["token"] for r in debug["internal_terms"]["replaced"]])

    def test_unknown_aspects_also_replaced(self):
        messages = _tool_messages({"duration_days": 3.5})
        structured = {
            "summary": "요약입니다.",
            "evidence": [],
            "unknown_aspects": ["duration_days를 계산할 수 없는 이슈가 있습니다."],
        }

        orchestrator._sanitize_internal_terms(structured, messages, 0, None)

        self.assertEqual(
            ["진행 기간(일)을 계산할 수 없는 이슈가 있습니다."], structured["unknown_aspects"]
        )

    def test_evidence_quote_never_touched(self):
        # quote는 사용자 원문이다 — summary를 고치더라도 quote는 그대로 둔다.
        quote = "랭킹 로직을 추가했다"
        messages = _tool_messages({"discussion_count": 12, "body": quote})
        structured = {
            "summary": "discussion_count가 12건입니다.",
            "evidence": [{"type": "commit", "id": "abc1234", "quote": quote}],
            "unknown_aspects": [],
        }

        orchestrator._sanitize_internal_terms(structured, messages, 0, None)

        self.assertEqual(quote, structured["evidence"][0]["quote"])
        self.assertNotIn("discussion_count", structured["summary"])

    def test_token_in_user_content_is_not_replaced(self):
        # 정밀 키 게이트 — 도구 결과의 **키이면서 동시에** 커밋·PR 본문에도 등장하면 사용자
        # 어휘로 본다. 실측(case-16): 질문이 "PullRequest 노드에 created_at을 추가한 배경은?"
        # 이었는데, created_at이 우리 필드이기도 해서 답변에서 그 이름이 지워졌다.
        messages = _tool_messages({
            "created_at": "2026-05-01T00:00:00Z",
            "message": "feat: PullRequest 노드에 created_at 추가",
        })
        structured = {
            "summary": "PullRequest 노드에 created_at을 추가했습니다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._sanitize_internal_terms(structured, messages, 0, debug)

        self.assertIn("created_at", structured["summary"])
        self.assertEqual([], debug["internal_terms"]["replaced"])

    def test_token_only_as_key_is_still_replaced(self):
        # 반대쪽 — 본문에 없고 키로만 있으면 우리 필드가 맞으므로 치환한다.
        messages = _tool_messages({"discussion_count": 12, "message": "글로벌 검색 추가"})
        structured = {"summary": "discussion_count가 12건입니다.", "evidence": [], "unknown_aspects": []}

        orchestrator._sanitize_internal_terms(structured, messages, 0, None)

        self.assertEqual("관련 대화 메시지 수가 12건입니다.", structured["summary"])

    def test_timestamp_field_replaced_when_only_our_key(self):
        # 시간 필드도 치환 대상이다. 도구 결과에 키로만 있으면 우리 필드이므로 사람 말로 바꾼다.
        messages = _tool_messages({"occurredAt": "2026-05-01T00:00:00Z"})
        structured = {
            "summary": "occurredAt 기준으로 정렬했습니다.",
            "evidence": [],
            "unknown_aspects": [],
        }

        orchestrator._sanitize_internal_terms(structured, messages, 0, None)

        self.assertEqual("발생 시각 기준으로 정렬했습니다.", structured["summary"])

    def test_timestamp_field_kept_when_user_data_mentions_it(self):
        # 반대쪽 — 커밋·PR 본문이 그 필드를 말하고 있으면 사용자 스키마 이야기이므로 보존한다.
        # 실측(case-16 "PullRequest 노드에 created_at을 추가한 배경은?"): 커밋 메시지가 그
        # 필드명을 말하고 있어, 이 게이트가 질문이 물은 이름을 지키는 유일한 방어다.
        messages = _tool_messages({
            "created_at": "2026-05-01T00:00:00Z",
            "message": "feat: PullRequest 노드에 created_at 추가",
        })
        structured = {
            "summary": "PullRequest 노드에 created_at을 추가했습니다.",
            "evidence": [],
            "unknown_aspects": [],
        }

        orchestrator._sanitize_internal_terms(structured, messages, 0, None)

        self.assertIn("created_at", structured["summary"])

    def test_token_absent_as_key_is_detected_but_not_replaced(self):
        # 사용자 코드에 같은 이름의 심볼이 있어도 우리 도구 결과의 키가 아니면 치환하지 않는다.
        messages = _tool_messages({"body": "discussion_count 컬럼을 추가했다"})
        structured = {
            "summary": "이 커밋은 discussion_count 컬럼을 추가했습니다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._sanitize_internal_terms(structured, messages, 0, debug)

        self.assertIn("discussion_count", structured["summary"])
        self.assertEqual([], debug["internal_terms"]["replaced"])
        self.assertEqual(
            ["discussion_count"], [d["token"] for d in debug["internal_terms"]["detected"]]
        )

    def test_backtick_wrapped_token_replaced_without_backticks(self):
        messages = _tool_messages({"discussion_count": 12})
        structured = {
            "summary": "`discussion_count`가 12건입니다.",
            "evidence": [],
            "unknown_aspects": [],
        }

        orchestrator._sanitize_internal_terms(structured, messages, 0, None)

        self.assertEqual("관련 대화 메시지 수가 12건입니다.", structured["summary"])

    def test_longer_identifier_not_partially_replaced(self):
        # 부분 일치로 남의 식별자를 훼손하면 안 된다 (my_discussion_count_total 안의 조각).
        messages = _tool_messages({"discussion_count": 12})
        structured = {
            "summary": "my_discussion_count_total 변수를 추가했습니다.",
            "evidence": [],
            "unknown_aspects": [],
        }

        orchestrator._sanitize_internal_terms(structured, messages, 0, None)

        self.assertEqual("my_discussion_count_total 변수를 추가했습니다.", structured["summary"])

    def test_detect_only_vocabulary_logged_not_replaced(self):
        # 라벨·관계 타입·도구 이름은 표기를 사람이 정하지 않았으므로 치환하지 않고 관측만 한다.
        messages = _tool_messages({"discussion_count": 12})
        structured = {
            "summary": "DISCUSSED_IN 관계를 rank_issues로 조회했습니다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._sanitize_internal_terms(structured, messages, 0, debug)

        self.assertEqual("DISCUSSED_IN 관계를 rank_issues로 조회했습니다.", structured["summary"])
        detected = {d["token"] for d in debug["internal_terms"]["detected"]}
        self.assertEqual({"DISCUSSED_IN", "rank_issues"}, detected)

    def test_everyday_words_untouched(self):
        # message·source·title 같은 일상어는 치환 대상이 아니다 (문장이 깨진다).
        messages = _tool_messages({"message": "커밋 메시지", "source": "JIRA", "title": "제목"})
        summary = "이 message의 source와 title을 확인했습니다."
        structured = {"summary": summary, "evidence": [], "unknown_aspects": []}
        debug: dict = {}

        orchestrator._sanitize_internal_terms(structured, messages, 0, debug)

        self.assertEqual(summary, structured["summary"])
        self.assertNotIn("internal_terms", debug)

    def test_multiple_occurrences_all_replaced_and_counted(self):
        messages = _tool_messages({"discussion_count": 12})
        structured = {
            "summary": "discussion_count 기준 1위이며, discussion_count는 12입니다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._sanitize_internal_terms(structured, messages, 0, debug)

        self.assertNotIn("discussion_count", structured["summary"])
        self.assertEqual(2, debug["internal_terms"]["replaced"][0]["count"])

    def test_clean_summary_records_nothing(self):
        messages = _tool_messages({"discussion_count": 12})
        structured = {
            "summary": "HT-129에 관련 대화 메시지가 12건 연결돼 있습니다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._sanitize_internal_terms(structured, messages, 0, debug)

        self.assertNotIn("internal_terms", debug)

    def test_debug_none_does_not_raise(self):
        messages = _tool_messages({"discussion_count": 12})
        structured = {
            "summary": "discussion_count가 12건입니다.",
            "evidence": [],
            "unknown_aspects": [],
        }

        orchestrator._sanitize_internal_terms(structured, messages, 0, None)

        self.assertEqual("관련 대화 메시지 수가 12건입니다.", structured["summary"])

    def test_missing_fields_do_not_raise(self):
        # structured가 summary만 있거나 값이 None이어도 터지지 않아야 한다.
        messages = _tool_messages({"discussion_count": 12})
        structured = {"summary": None, "evidence": None, "unknown_aspects": None}

        orchestrator._sanitize_internal_terms(structured, messages, 0, None)


class InternalIdDetectionTest(unittest.TestCase):
    """내부 식별자 '값'(슬랙 ts·UUID) 검출 — 치환하지 않고 세기만 한다.

    실기동에서 "슬랙 스레드 1786776420.322659에서 …"처럼 본문에 그대로 실렸다. 이름이 아니라
    값이라 무엇으로 바꿀지가 아니라 빼야 하는 것이고, 서버가 문장에서 토큰을 삭제하면 치환보다
    문장을 깨뜨릴 위험이 커서 프롬프트로 막고 여기서는 관측만 한다.
    """

    def _detected(self, summary: str) -> dict:
        structured = {"summary": summary, "evidence": [], "unknown_aspects": []}
        debug: dict = {}
        orchestrator._sanitize_internal_terms(structured, _tool_messages({}), 0, debug)
        return {
            d["token"]: d["count"]
            for d in (debug.get("internal_terms") or {}).get("detected") or []
        }, structured["summary"]

    def test_slack_ts_detected_but_text_unchanged(self):
        summary = "슬랙 스레드 1786776420.322659에서 봇 도입을 논의했습니다."
        detected, text = self._detected(summary)

        self.assertEqual(1, detected["대화 스레드 ID"])
        self.assertEqual(summary, text)  # 값은 치환하지 않는다

    def test_uuid_detected(self):
        detected, _ = self._detected("프로젝트 eb74cbd9-33ce-4c0e-9272-d30a661830f3의 결과입니다.")

        self.assertEqual(1, detected["내부 UUID"])

    def test_commit_hash_not_detected(self):
        # 커밋 해시는 사용자가 실제로 쓰는 식별자라 대상이 아니다 (하이픈 없는 hex).
        detected, _ = self._detected("커밋 8cdb0cc와 1739b7a에서 수정했습니다.")

        self.assertEqual({}, detected)

    def test_ordinary_numbers_not_detected(self):
        # 기간·날짜·버전 숫자가 스레드 ID로 오인되면 지표가 죽는다.
        detected, _ = self._detected("2026-08-15에 종료됐고 진행 기간은 50.8일, 1.2.3 버전입니다.")

        self.assertEqual({}, detected)


class ParticleCorrectionTest(unittest.TestCase):
    """치환 뒤 조사 교정 — 모델은 영어 토큰 발음에 맞춰 조사를 고르므로 그대로 두면 어긋난다."""

    def _sanitized(self, summary: str, payload: dict) -> str:
        structured = {"summary": summary, "evidence": [], "unknown_aspects": []}
        orchestrator._sanitize_internal_terms(structured, _tool_messages(payload), 0, None)
        return structured["summary"]

    def test_particle_switched_when_batchim_changes(self):
        # duration_days를 → 진행 기간(일)"을"  (괄호로 끝나도 발음은 앞의 '일'이라 받침이 있다)
        self.assertEqual(
            "진행 기간(일)을 계산했습니다.",
            self._sanitized("duration_days를 계산했습니다.", {"duration_days": 3.5}),
        )
        self.assertEqual(
            "진행 기간(일)은 3.5일입니다.",
            self._sanitized("duration_days는 3.5일입니다.", {"duration_days": 3.5}),
        )

    def test_particle_kept_when_already_correct(self):
        self.assertEqual(
            "관련 대화 메시지 수가 12건입니다.",
            self._sanitized("discussion_count가 12건입니다.", {"discussion_count": 12}),
        )

    def test_rieul_batchim_uses_ro(self):
        # ㄹ 받침은 '으로'가 아니라 '로'다 — 일반 받침 규칙과 다르다.
        self.assertEqual(
            "진행 기간(일)로 정렬했습니다.",
            self._sanitized("duration_days으로 정렬했습니다.", {"duration_days": 3.5}),
        )

    def test_hangul_word_after_token_is_not_treated_as_particle(self):
        # 뒤에 한글이 이어지면 조사가 아니라 단어의 첫 글자다 — 삼키면 안 된다.
        self.assertEqual(
            "관련 대화 메시지 수은행 관련입니다.",
            self._sanitized("discussion_count은행 관련입니다.", {"discussion_count": 12}),
        )

    def test_no_particle_after_token(self):
        self.assertEqual(
            "관련 대화 메시지 수 기준 1위입니다.",
            self._sanitized("discussion_count 기준 1위입니다.", {"discussion_count": 12}),
        )


class GlossaryConsistencyTest(unittest.TestCase):
    def test_every_metric_term_has_label_and_definition(self):
        for token, (label, definition) in glossary.METRIC_TERMS.items():
            with self.subTest(token=token):
                self.assertTrue(label.strip(), f"{token}에 표기가 없다")
                self.assertTrue(definition.strip(), f"{token}에 정의가 없다")

    def test_replacements_do_not_overlap_never_replace(self):
        overlap = set(glossary.REPLACEMENTS) & glossary.NEVER_REPLACE
        self.assertEqual(set(), overlap, f"일상어를 치환 대상에 넣으면 문장이 깨진다: {overlap}")

    def test_detect_only_does_not_overlap_replacements(self):
        overlap = set(glossary.DETECT_ONLY) & set(glossary.REPLACEMENTS)
        self.assertEqual(set(), overlap)

    def test_timestamp_fields_are_replacement_targets(self):
        # 시간 필드는 치환 목록에 남긴다(2026-08-21 결정). 자기참조 상황의 오치환은
        # 정밀 키 게이트가 막는다 — is_our_field 참고.
        for token in ("occurredAt", "createdAt", "created_at", "closedAt", "closed_at", "merged_at"):
            with self.subTest(token=token):
                self.assertIn(token, glossary.REPLACEMENTS)

    def test_detect_only_is_derived_from_code(self):
        # 손으로 쓰지 않고 코드에서 파생한다 — 스키마·도구가 바뀌면 자동으로 따라온다.
        self.assertIn("DISCUSSED_IN", glossary.DETECT_ONLY)
        self.assertIn("rank_issues", glossary.DETECT_ONLY)
        self.assertIn("PullRequest", glossary.DETECT_ONLY)
        # 일상적인 영어 단어인 라벨은 관측 오탐을 만들므로 제외한다.
        self.assertNotIn("Issue", glossary.DETECT_ONLY)

    def test_prompt_glossary_contains_labels_and_definitions(self):
        rendered = glossary.prompt_glossary()
        self.assertIn("discussion_count → 관련 대화 메시지 수", rendered)
        self.assertIn("내용 유사도로 추정한 연결", rendered)
        self.assertIn("occurredAt → 발생 시각", rendered)

    def test_system_prompt_carries_the_rule_and_glossary(self):
        self.assertIn("내부 용어 노출 금지", orchestrator._SYSTEM_PROMPT)
        self.assertIn("discussion_count → 관련 대화 메시지 수", orchestrator._SYSTEM_PROMPT)

    def test_system_prompt_bans_id_values_and_implementation_asides(self):
        # 실기동에서 드러난 두 가지 — 스레드 ID 값 노출과 "내부 계산식은 확인되지 않음" 사족.
        self.assertIn("내부 식별자 '값'도 본문에 쓰지 마세요", orchestrator._SYSTEM_PROMPT)
        self.assertIn("내부 계산식·구현까지는 확인되지 않는다", orchestrator._SYSTEM_PROMPT)


if __name__ == "__main__":
    unittest.main()
