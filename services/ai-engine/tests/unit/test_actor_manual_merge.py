"""Actor 수동 분리 결정(veto)의 resolver 통합 단위 테스트 (오프라인 — ActorStore mock 주입).

수동 distinct 결정이 자동 병합을 이기는지 검증한다 (docs/actor-manual-merge.md):
- Step 1: email이 정확히 매칭돼도 veto된 Actor로는 병합하지 않는다.
- Step 2: veto된 후보는 스코어링·LLM 판단 대상에서 제외된다.
- lookup_vetoes 미주입(None)이면 기존 동작 그대로다 (하위 호환).
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_resolver import ActorStore, resolve_actor

VETOED_ACTOR = {
    "uuid": "vetoed-uuid",
    "name": "John Doe",
    "aliases": ["JIRA:john"],
    "emails": ["john@company.com"],
}

OTHER_ACTOR = {
    "uuid": "other-uuid",
    "name": "John Doe",
    "aliases": ["SLACK:jdoe"],
    "emails": ["jdoe@company.com"],
}

NEW_EVENT_ACTOR = {"id": "john-gh", "name": "John Doe", "email": "john@company.com"}


def _store(
    *,
    by_email=None,
    by_name=None,
    vetoes=None,
    with_vetoes_field=True,
) -> tuple[ActorStore, dict]:
    """mock ActorStore와 호출 기록(calls)을 만든다."""
    calls = {"merged": [], "created": []}

    async def lookup_by_alias(source_id):
        return None

    async def lookup_by_email(email):
        return by_email

    async def lookup_by_name(name):
        return list(by_name or [])

    async def lookup_activities(actor):
        return []

    async def merge_actor(actor, new_alias, new_email, new_name):
        calls["merged"].append((actor["uuid"], new_alias, new_name))

    async def create_actor(name, source_id, email):
        created = {"uuid": "new-uuid", "name": name, "aliases": [source_id], "emails": [email] if email else []}
        calls["created"].append(created)
        return created

    kwargs = dict(
        lookup_by_alias=lookup_by_alias,
        lookup_by_email=lookup_by_email,
        lookup_by_name=lookup_by_name,
        lookup_activities=lookup_activities,
        merge_actor=merge_actor,
        create_actor=create_actor,
    )
    if with_vetoes_field:
        async def lookup_vetoes(source_id):
            return list(vetoes or [])
        kwargs["lookup_vetoes"] = lookup_vetoes
    return ActorStore(**kwargs), calls


class VetoBlocksEmailMerge(unittest.TestCase):
    def test_step1_email_match_vetoed_falls_through(self):
        """email이 매칭돼도 distinct 결정이 있으면 병합하지 않고 신규 생성으로 간다."""
        store, calls = _store(by_email=VETOED_ACTOR, vetoes=["vetoed-uuid"])
        result = asyncio.run(resolve_actor(NEW_EVENT_ACTOR, "GITHUB", store))

        self.assertEqual(calls["merged"], [])
        self.assertEqual(len(calls["created"]), 1)
        self.assertEqual(result["uuid"], "new-uuid")

    def test_step1_email_match_without_veto_merges(self):
        """veto가 없으면 기존처럼 email 매칭으로 병합한다."""
        store, calls = _store(by_email=VETOED_ACTOR, vetoes=[])
        result = asyncio.run(resolve_actor(NEW_EVENT_ACTOR, "GITHUB", store))

        self.assertEqual(len(calls["merged"]), 1)
        self.assertEqual(calls["merged"][0][0], "vetoed-uuid")
        self.assertEqual(result["uuid"], "vetoed-uuid")


class VetoExcludesNameCandidates(unittest.TestCase):
    def test_vetoed_candidate_skips_llm(self):
        """veto된 후보는 LLM 판단 대상에서 제외되고, 나머지 후보는 정상 판단된다."""
        store, calls = _store(by_name=[VETOED_ACTOR, OTHER_ACTOR], vetoes=["vetoed-uuid"])
        judged: list[str] = []

        async def fake_judge(candidate, activities, actor, source, event):
            judged.append(candidate["uuid"])
            return {"same_person": True, "confidence": 0.95, "key_signals": [], "reason": ""}

        with patch("graph.actor_resolver.judge_same_person", side_effect=fake_judge):
            result = asyncio.run(
                resolve_actor({"id": "john-gh", "name": "John Doe"}, "GITHUB", store)
            )

        self.assertNotIn("vetoed-uuid", judged)
        self.assertEqual(judged, ["other-uuid"])
        self.assertEqual(result["uuid"], "other-uuid")

    def test_all_candidates_vetoed_creates_new(self):
        """후보 전원이 veto되면 LLM 호출 없이 신규 Actor를 생성한다."""
        store, calls = _store(
            by_name=[VETOED_ACTOR, OTHER_ACTOR], vetoes=["vetoed-uuid", "other-uuid"]
        )

        async def fail_judge(*args, **kwargs):
            raise AssertionError("veto된 후보에 LLM 판단이 호출되면 안 된다")

        with patch("graph.actor_resolver.judge_same_person", side_effect=fail_judge):
            result = asyncio.run(
                resolve_actor({"id": "john-gh", "name": "John Doe"}, "GITHUB", store)
            )

        self.assertEqual(result["uuid"], "new-uuid")


class BackwardCompatibility(unittest.TestCase):
    def test_store_without_lookup_vetoes_field(self):
        """lookup_vetoes 미주입(None) 스토어는 기존 동작 그대로 병합한다."""
        store, calls = _store(by_email=VETOED_ACTOR, with_vetoes_field=False)
        result = asyncio.run(resolve_actor(NEW_EVENT_ACTOR, "GITHUB", store))

        self.assertEqual(len(calls["merged"]), 1)
        self.assertEqual(result["uuid"], "vetoed-uuid")


if __name__ == "__main__":
    unittest.main()
