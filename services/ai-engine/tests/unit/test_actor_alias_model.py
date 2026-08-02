"""Actor 신원 모델 개편(개인정보를 ActorAlias로 이전) 단위 테스트 (오프라인 — Neo4j 없이 mock).

세 가지를 고정한다 (docs/actor-identity-model.md):
- derive_display_name의 표시 이름 폴백 체인
- resolve_actor Step 0의 이름 갱신 — "closed" alias는 절대 갱신하지 않는다(삭제 정합성의
  유일한 방어선), "access_lost"는 갱신한다(재연동 복구 경로)
- resolve_actor Step 1/3/4가 ActorStore의 새 시그니처(merge_actor에 name, create_actor에
  source_id·email 단일값)로 호출되는지
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_resolver import ActorStore, _score_candidate, resolve_actor
from graph.actor_store import derive_display_name


# ── derive_display_name ──────────────────────────────────────────────────


class DeriveDisplayName(unittest.TestCase):
    def test_github_profile_wins_regardless_of_activity(self):
        """(a) GitHub 프로필 이름이 있으면 다른 소스의 활동량이 훨씬 많아도 최우선이다."""
        aliases = [
            {"source_id": "SLACK:U1", "source": "SLACK", "pd_name": "영희"},
            {"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": "김영희"},
            {"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "Younghee Kim"},
        ]
        activity = {"SLACK": 1000, "JIRA": 500, "GITHUB": 1}
        self.assertEqual(derive_display_name(aliases, False, None, activity), "Younghee Kim")

    def test_more_active_source_wins_when_no_github(self):
        """(b) GitHub이 없으면 고정 서열(Jira>Slack) 대신 소스 활동량이 더 많은 쪽이 이긴다."""
        aliases = [
            {"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": "김영희"},
            {"source_id": "SLACK:U1", "source": "SLACK", "pd_name": "영희"},
        ]
        activity = {"JIRA": 10, "SLACK": 90}
        self.assertEqual(derive_display_name(aliases, False, None, activity), "영희")

    def test_tied_activity_breaks_by_source_name_lexicographic_order(self):
        """(c) 활동량이 동률이면 소스명 사전순으로 정한다."""
        aliases = [
            {"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": "김영희"},
            {"source_id": "SLACK:U1", "source": "SLACK", "pd_name": "영희"},
        ]
        activity = {"JIRA": 5, "SLACK": 5}
        self.assertEqual(derive_display_name(aliases, False, None, activity), "김영희")

    def test_activity_by_source_omitted_keeps_deterministic_order(self):
        """(d) activity_by_source를 안 주면(None) 모든 소스를 활동량 0으로 보고 소스명
        사전순으로 정한다 — 우연히 기존 GitHub>Jira>Slack 고정 서열과 알파벳 순서가 같아
        결과도 같다."""
        aliases = [
            {"source_id": "SLACK:U1", "source": "SLACK", "pd_name": "영희"},
            {"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": "김영희"},
        ]
        self.assertEqual(derive_display_name(aliases, False, None), "김영희")

    def test_placeholder_github_activity_does_not_override_exclusion(self):
        """(e) placeholder GitHub(pd_name==login)은 활동량이 커도 후보 비교에 안 낀다 —
        login 폴백은 다른 소스가 전혀 없을 때만 쓰인다."""
        aliases = [
            {"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "se-zero"},
            {"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": "김영희"},
        ]
        activity = {"GITHUB": 1000, "JIRA": 1}
        self.assertEqual(derive_display_name(aliases, False, None, activity), "김영희")

    def test_falls_back_to_slack_when_only_slack(self):
        aliases = [{"source_id": "SLACK:U1", "source": "SLACK", "pd_name": "영희"}]
        self.assertEqual(derive_display_name(aliases, False, None), "영희")

    def test_falls_back_to_github_login_when_github_pd_name_empty(self):
        aliases = [{"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": ""}]
        self.assertEqual(derive_display_name(aliases, False, None), "se-zero")

    def test_manual_name_keeps_current_name(self):
        aliases = [{"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "Younghee Kim"}]
        self.assertEqual(derive_display_name(aliases, True, "김영희(전 PM)"), "김영희(전 PM)")

    def test_empty_pd_name_excluded_from_candidates(self):
        aliases = [
            {"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": ""},
            {"source_id": "SLACK:U1", "source": "SLACK", "pd_name": "영희"},
        ]
        self.assertEqual(derive_display_name(aliases, False, None), "영희")

    def test_all_names_empty_returns_deleted_user_label(self):
        aliases = [{"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": ""}]
        self.assertEqual(derive_display_name(aliases, False, None), "(삭제된 사용자)")
        self.assertEqual(derive_display_name([], False, None), "(삭제된 사용자)")

    def test_same_source_multiple_aliases_pick_lowest_source_id_deterministically(self):
        aliases = [
            {"source_id": "JIRA:zzz", "source": "JIRA", "pd_name": "Z Name"},
            {"source_id": "JIRA:aaa", "source": "JIRA", "pd_name": "A Name"},
        ]
        self.assertEqual(derive_display_name(aliases, False, None), "A Name")

    def test_github_pd_name_equal_to_login_falls_back_to_jira(self):
        """pipeline-worker는 GitHub 프로필 이름이 비어 있으면 login을 pd_name에 그대로 채워
        보낸다 — 이 경우 "프로필 이름 있음"으로 착각해 Jira/Slack 실명보다 우선시키면 안 된다."""
        aliases = [
            {"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "se-zero"},
            {"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": "김영희"},
        ]
        self.assertEqual(derive_display_name(aliases, False, None), "김영희")

    def test_github_pd_name_equal_to_login_and_no_other_source_falls_back_to_login(self):
        aliases = [{"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "se-zero"}]
        self.assertEqual(derive_display_name(aliases, False, None), "se-zero")

    def test_github_pd_name_differs_from_login_still_takes_priority(self):
        """실제 GitHub 프로필 이름(login과 다름)은 기존대로 최우선 — 이미 다른 테스트가
        커버하는 시나리오지만(test_github_profile_wins_regardless_of_activity) 회귀 방지로 명시한다."""
        aliases = [
            {"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "Younghee Kim"},
            {"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": "김영희"},
        ]
        self.assertEqual(derive_display_name(aliases, False, None), "Younghee Kim")


# ── resolve_actor Step 0 — 이름 갱신 ──────────────────────────────────────


def _step0_store(*, alias_hit: dict, with_update_alias_name: bool):
    """Step 0 alias 히트를 고정하고 update_alias_name 호출만 기록하는 mock ActorStore."""
    calls: list[tuple[str, str]] = []

    async def lookup_by_alias(source_id):
        return alias_hit

    async def lookup_by_email(email):
        return None

    async def lookup_by_name(name):
        return []

    async def lookup_activities(actor):
        return []

    async def merge_actor(actor, new_alias, new_email, new_name):
        raise AssertionError("Step 0에서 alias가 히트하면 merge_actor는 호출되면 안 된다")

    async def create_actor(name, source_id, email):
        raise AssertionError("Step 0에서 alias가 히트하면 create_actor는 호출되면 안 된다")

    kwargs = dict(
        lookup_by_alias=lookup_by_alias,
        lookup_by_email=lookup_by_email,
        lookup_by_name=lookup_by_name,
        lookup_activities=lookup_activities,
        merge_actor=merge_actor,
        create_actor=create_actor,
    )
    if with_update_alias_name:
        async def update_alias_name(source_id, name):
            calls.append((source_id, name))
        kwargs["update_alias_name"] = update_alias_name
    return ActorStore(**kwargs), calls


class ResolveActorStep0NameUpdate(unittest.TestCase):
    def test_same_name_skips_update(self):
        alias_hit = {
            "uuid": "u1", "name": "Younghee Kim", "aliases": ["GITHUB:se-zero"],
            "alias_pd_name": "Younghee Kim", "alias_pd_erased": None,
        }
        store, calls = _step0_store(alias_hit=alias_hit, with_update_alias_name=True)
        result = asyncio.run(
            resolve_actor({"id": "se-zero", "name": "Younghee Kim"}, "GITHUB", store)
        )
        self.assertEqual(calls, [])
        self.assertEqual(result["uuid"], "u1")

    def test_different_name_calls_update(self):
        alias_hit = {
            "uuid": "u1", "name": "김영희", "aliases": ["JIRA:5b10a2"],
            "alias_pd_name": "김영희", "alias_pd_erased": None,
        }
        store, calls = _step0_store(alias_hit=alias_hit, with_update_alias_name=True)
        asyncio.run(resolve_actor({"id": "5b10a2", "name": "영희"}, "JIRA", store))
        self.assertEqual(calls, [("JIRA:5b10a2", "영희")])

    def test_closed_alias_is_never_updated(self):
        """계정 폐쇄로 지운 이름은 재수집으로 절대 되살아나면 안 된다 — 삭제 정합성의 핵심 가드."""
        alias_hit = {
            "uuid": "u1", "name": "Younghee Kim", "aliases": ["JIRA:5b10a2"],
            "alias_pd_name": None, "alias_pd_erased": "closed",
        }
        store, calls = _step0_store(alias_hit=alias_hit, with_update_alias_name=True)
        asyncio.run(resolve_actor({"id": "5b10a2", "name": "김영희"}, "JIRA", store))
        self.assertEqual(calls, [])

    def test_access_lost_alias_is_updated(self):
        """재조회 불가로 비워둔 휴면 alias는 재연동 후 수집이 다시 채우는 복구 경로다."""
        alias_hit = {
            "uuid": "u1", "name": "Younghee Kim", "aliases": ["JIRA:5b10a2"],
            "alias_pd_name": None, "alias_pd_erased": "access_lost",
        }
        store, calls = _step0_store(alias_hit=alias_hit, with_update_alias_name=True)
        asyncio.run(resolve_actor({"id": "5b10a2", "name": "김영희"}, "JIRA", store))
        self.assertEqual(calls, [("JIRA:5b10a2", "김영희")])

    def test_missing_update_alias_name_field_is_backward_compatible(self):
        """update_alias_name 미주입(None, 구형 mock) 스토어는 에러 없이 동작한다."""
        alias_hit = {
            "uuid": "u1", "name": "Younghee Kim", "aliases": ["JIRA:5b10a2"],
            "alias_pd_name": "김영희", "alias_pd_erased": None,
        }
        store, _ = _step0_store(alias_hit=alias_hit, with_update_alias_name=False)
        result = asyncio.run(
            resolve_actor({"id": "5b10a2", "name": "다른이름"}, "JIRA", store)
        )
        self.assertEqual(result["uuid"], "u1")


# ── resolve_actor Step 1/3/4 — ActorStore 새 시그니처 ─────────────────────


class ResolveActorStep1MergeSignature(unittest.TestCase):
    def test_email_match_calls_merge_actor_with_name_and_email(self):
        existing = {"uuid": "u1", "name": "John Doe", "aliases": ["JIRA:john"], "emails": ["john@company.com"]}
        calls = []

        async def lookup_by_alias(source_id):
            return None

        async def lookup_by_email(email):
            return existing

        async def lookup_by_name(name):
            return []

        async def lookup_activities(actor):
            return []

        async def merge_actor(actor, new_alias, new_email, new_name):
            calls.append((actor["uuid"], new_alias, new_email, new_name))

        async def create_actor(name, source_id, email):
            raise AssertionError("Step 1이 매칭됐으면 create_actor는 호출되면 안 된다")

        store = ActorStore(
            lookup_by_alias=lookup_by_alias, lookup_by_email=lookup_by_email,
            lookup_by_name=lookup_by_name, lookup_activities=lookup_activities,
            merge_actor=merge_actor, create_actor=create_actor,
        )
        result = asyncio.run(resolve_actor(
            {"id": "john-gh", "name": "John Doe", "email": "john@company.com"}, "GITHUB", store,
        ))

        self.assertEqual(calls, [("u1", "GITHUB:john-gh", "john@company.com", "John Doe")])
        self.assertEqual(result["uuid"], "u1")


class ResolveActorStep3MergeSignature(unittest.TestCase):
    def test_llm_match_calls_merge_actor_with_name_and_email(self):
        candidate = {"uuid": "cand-uuid", "name": "김철수", "aliases": ["SLACK:U1"], "emails": []}
        calls = []

        async def lookup_by_alias(source_id):
            return None

        async def lookup_by_email(email):
            return None

        async def lookup_by_name(name):
            return [candidate]

        async def lookup_activities(actor):
            return []

        async def merge_actor(actor, new_alias, new_email, new_name):
            calls.append((actor["uuid"], new_alias, new_email, new_name))

        async def create_actor(name, source_id, email):
            raise AssertionError("Step 3이 매칭됐으면 create_actor는 호출되면 안 된다")

        store = ActorStore(
            lookup_by_alias=lookup_by_alias, lookup_by_email=lookup_by_email,
            lookup_by_name=lookup_by_name, lookup_activities=lookup_activities,
            merge_actor=merge_actor, create_actor=create_actor,
        )

        async def fake_judge(candidate_arg, activities, actor, source, event):
            return {"same_person": True, "confidence": 0.95, "key_signals": [], "reason": ""}

        with patch("graph.actor_resolver.judge_same_person", side_effect=fake_judge):
            result = asyncio.run(resolve_actor({"id": "gh-chulsu", "name": "김철수"}, "GITHUB", store))

        self.assertEqual(calls, [("cand-uuid", "GITHUB:gh-chulsu", None, "김철수")])
        self.assertEqual(result["uuid"], "cand-uuid")


class ResolveActorStep4CreateSignature(unittest.TestCase):
    def test_no_match_calls_create_actor_with_name_source_id_email(self):
        calls = []

        async def lookup_by_alias(source_id):
            return None

        async def lookup_by_email(email):
            return None

        async def lookup_by_name(name):
            return []

        async def lookup_activities(actor):
            return []

        async def merge_actor(actor, new_alias, new_email, new_name):
            raise AssertionError("후보가 없으면 merge_actor는 호출되면 안 된다")

        async def create_actor(name, source_id, email):
            calls.append((name, source_id, email))
            return {"uuid": "new-uuid", "name": name}

        store = ActorStore(
            lookup_by_alias=lookup_by_alias, lookup_by_email=lookup_by_email,
            lookup_by_name=lookup_by_name, lookup_activities=lookup_activities,
            merge_actor=merge_actor, create_actor=create_actor,
        )
        result = asyncio.run(resolve_actor(
            {"id": "se-zero", "name": "Younghee Kim", "email": "yh@corp.com"}, "GITHUB", store,
        ))

        self.assertEqual(calls, [("Younghee Kim", "GITHUB:se-zero", "yh@corp.com")])
        self.assertEqual(result["uuid"], "new-uuid")


# ── _score_candidate — alias에서 모은 emails로 기존 스코어링 동작 유지 ────


class ScoreCandidateReadsAliasCollectedEmails(unittest.TestCase):
    def test_matching_email_local_part_and_domain_scores_above_base(self):
        candidate = {"uuid": "u1", "name": "John Doe", "emails": ["john.doe@acme.com"]}
        score = _score_candidate("john.doe@acme.com", candidate)
        self.assertGreater(score, 0.5)

    def test_missing_emails_key_falls_back_to_base_score(self):
        candidate = {"uuid": "u1", "name": "John Doe"}
        score = _score_candidate("john.doe@acme.com", candidate)
        self.assertEqual(score, 0.5)


if __name__ == "__main__":
    unittest.main()
