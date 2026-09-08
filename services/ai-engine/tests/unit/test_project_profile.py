"""project_profile 순수 함수·캐시 오프라인 단위 테스트.

Neo4j·OpenAI 없이 동작한다. chat_completion은 patch.object로 모킹하고(test_slack_llm_filter.py와
동일한 방식), get_project_profile의 캐시·TTL은 fetch_profile_material/summarize_material을
patch해 time.monotonic으로 시간 경과를 흉내낸다.
"""

import os
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from graph import project_profile


def _stub_response(content: str):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))])


def _material(**overrides) -> dict:
    base = {
        "repo_names": [],
        "pr_titles": [],
        "issue_titles": [],
        "commit_messages": [],
        "top_dirs": [],
        "document_titles": [],
    }
    base.update(overrides)
    return base


class RepoNamesFromUrlsTest(unittest.TestCase):
    def test_same_repo_urls_collapse_to_one_entry(self):
        urls = [
            "https://github.com/acme/payflow/pull/12",
            "https://github.com/acme/payflow/pull/13",
        ]
        self.assertEqual(project_profile.repo_names_from_urls(urls), ["acme/payflow"])

    def test_different_repos_keep_first_seen_order(self):
        urls = [
            "https://github.com/acme/payflow/pull/12",
            "https://github.com/acme/other-repo/pull/1",
        ]
        self.assertEqual(
            project_profile.repo_names_from_urls(urls), ["acme/payflow", "acme/other-repo"]
        )

    def test_unparseable_url_is_ignored(self):
        urls = ["not-a-url", "https://github.com/acme/payflow/pull/12", ""]
        self.assertEqual(project_profile.repo_names_from_urls(urls), ["acme/payflow"])


class TopDirsFromPathsTest(unittest.TestCase):
    def test_truncates_at_depth_and_excludes_dirless_paths(self):
        paths = [
            "services/ai-engine/graph/a.py",
            "services/ai-engine/graph/b.py",
            "services/backend/src/Foo.java",
            "README.md",
        ]
        result = project_profile.top_dirs_from_paths(paths, depth=2, k=10)
        self.assertEqual(result, ["services/ai-engine", "services/backend"])

    def test_k_limits_result_by_frequency_desc(self):
        paths = [
            "services/ai-engine/graph/a.py",
            "services/ai-engine/graph/b.py",
            "services/backend/src/Foo.java",
        ]
        result = project_profile.top_dirs_from_paths(paths, depth=2, k=1)
        self.assertEqual(result, ["services/ai-engine"])

    def test_tie_breaks_alphabetically(self):
        paths = ["b/x.py", "a/y.py"]
        result = project_profile.top_dirs_from_paths(paths, depth=2, k=10)
        self.assertEqual(result, ["a", "b"])


class MixedSampleTest(unittest.TestCase):
    def test_non_overlapping_lists_keep_latest_then_oldest_order(self):
        latest = [{"id": "a"}, {"id": "b"}]
        oldest = [{"id": "c"}, {"id": "d"}]
        result = project_profile.mixed_sample(latest, oldest, key=lambda x: x["id"])
        self.assertEqual(result, [{"id": "a"}, {"id": "b"}, {"id": "c"}, {"id": "d"}])

    def test_overlapping_items_are_dropped_from_oldest_side(self):
        latest = [{"id": "a"}, {"id": "b"}]
        oldest = [{"id": "b"}, {"id": "c"}]
        result = project_profile.mixed_sample(latest, oldest, key=lambda x: x["id"])
        self.assertEqual(result, [{"id": "a"}, {"id": "b"}, {"id": "c"}])

    def test_empty_lists(self):
        self.assertEqual(project_profile.mixed_sample([], [], key=lambda x: x["id"]), [])
        self.assertEqual(
            project_profile.mixed_sample([{"id": "a"}], [], key=lambda x: x["id"]),
            [{"id": "a"}],
        )
        self.assertEqual(
            project_profile.mixed_sample([], [{"id": "a"}], key=lambda x: x["id"]),
            [{"id": "a"}],
        )


class MaterialItemCountTest(unittest.TestCase):
    def test_counts_sum_of_three_lists(self):
        material = _material(pr_titles=["a", "b"], issue_titles=["c"], commit_messages=["d", "e", "f"])
        self.assertEqual(project_profile.material_item_count(material), 6)


class BuildSummaryPromptTest(unittest.TestCase):
    def test_prompt_includes_material_and_null_instruction(self):
        material = _material(
            repo_names=["acme/payflow"],
            pr_titles=["결제 재시도 로직 추가"],
            issue_titles=["[bug] 타임아웃 오류"],
            commit_messages=["fix: 타임아웃 처리"],
            top_dirs=["services/ai-engine"],
            document_titles=["결제 아키텍처 문서"],
        )
        prompt = project_profile.build_summary_prompt(material)

        for expected in (
            "acme/payflow",
            "결제 재시도 로직 추가",
            "[bug] 타임아웃 오류",
            "fix: 타임아웃 처리",
            "services/ai-engine",
            "결제 아키텍처 문서",
            "null",
        ):
            self.assertIn(expected, prompt)


class SummarizeMaterialTest(unittest.IsolatedAsyncioTestCase):
    async def test_below_min_items_skips_llm_and_returns_empty(self):
        material = _material(pr_titles=["a"] * 4, issue_titles=["b"] * 3, commit_messages=["c"] * 2)  # 합 9
        self.assertEqual(project_profile.material_item_count(material), 9)

        with patch.object(project_profile, "chat_completion", AsyncMock()) as mock_chat:
            result = await project_profile.summarize_material(material)

        self.assertEqual(result, "")
        mock_chat.assert_not_called()

    async def test_llm_null_response_normalizes_to_empty(self):
        material = _material(pr_titles=["a"] * 4, issue_titles=["b"] * 3, commit_messages=["c"] * 3)  # 합 10
        self.assertEqual(project_profile.material_item_count(material), 10)

        with patch.object(
            project_profile, "chat_completion", AsyncMock(return_value=_stub_response("null"))
        ):
            result = await project_profile.summarize_material(material)

        self.assertEqual(result, "")

    async def test_normal_sentence_is_returned_stripped(self):
        material = _material(pr_titles=["a"] * 10)
        with patch.object(
            project_profile,
            "chat_completion",
            AsyncMock(return_value=_stub_response("  결제 서비스를 만드는 프로젝트입니다.  ")),
        ):
            result = await project_profile.summarize_material(material)

        self.assertEqual(result, "결제 서비스를 만드는 프로젝트입니다.")


class TtlSecondsTest(unittest.TestCase):
    def test_empty_string_falls_back_to_default(self):
        with patch.dict(os.environ, {"PROJECT_PROFILE_TTL_SECONDS": ""}):
            self.assertEqual(project_profile._ttl_seconds(), 86400.0)

    def test_whitespace_only_falls_back_to_default(self):
        with patch.dict(os.environ, {"PROJECT_PROFILE_TTL_SECONDS": "   "}):
            self.assertEqual(project_profile._ttl_seconds(), 86400.0)

    def test_non_numeric_falls_back_to_default(self):
        with patch.dict(os.environ, {"PROJECT_PROFILE_TTL_SECONDS": "abc"}):
            self.assertEqual(project_profile._ttl_seconds(), 86400.0)

    def test_zero_falls_back_to_default(self):
        with patch.dict(os.environ, {"PROJECT_PROFILE_TTL_SECONDS": "0"}):
            self.assertEqual(project_profile._ttl_seconds(), 86400.0)

    def test_negative_falls_back_to_default(self):
        with patch.dict(os.environ, {"PROJECT_PROFILE_TTL_SECONDS": "-5"}):
            self.assertEqual(project_profile._ttl_seconds(), 86400.0)

    def test_valid_value_is_parsed(self):
        with patch.dict(os.environ, {"PROJECT_PROFILE_TTL_SECONDS": "100"}):
            self.assertEqual(project_profile._ttl_seconds(), 100.0)


class GetProjectProfileTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        project_profile.clear_profile_cache()

    async def test_second_call_within_ttl_uses_cache(self):
        with patch.object(
            project_profile, "fetch_profile_material", AsyncMock(return_value=_material())
        ) as mock_fetch, patch.object(
            project_profile, "summarize_material", AsyncMock(return_value="프로필 문장")
        ) as mock_summarize, patch.object(project_profile.time, "monotonic", return_value=0.0):
            first = await project_profile.get_project_profile("p1")
            second = await project_profile.get_project_profile("p1")

        self.assertEqual(first, "프로필 문장")
        self.assertEqual(second, "프로필 문장")
        mock_fetch.assert_awaited_once()
        mock_summarize.assert_awaited_once()

    async def test_ttl_expiry_triggers_refetch(self):
        with patch.dict(os.environ, {"PROJECT_PROFILE_TTL_SECONDS": "100"}), patch.object(
            project_profile, "fetch_profile_material", AsyncMock(return_value=_material())
        ) as mock_fetch, patch.object(
            project_profile, "summarize_material", AsyncMock(return_value="프로필 문장")
        ), patch.object(
            project_profile.time, "monotonic", side_effect=[0.0, 50.0, 200.0]
        ):
            await project_profile.get_project_profile("p1")  # t=0, fetch
            await project_profile.get_project_profile("p1")  # t=50, cache (50 < 100)
            self.assertEqual(mock_fetch.await_count, 1)
            await project_profile.get_project_profile("p1")  # t=200, expired, refetch

        self.assertEqual(mock_fetch.await_count, 2)

    async def test_different_project_ids_have_separate_cache_entries(self):
        with patch.object(
            project_profile, "fetch_profile_material", AsyncMock(return_value=_material())
        ) as mock_fetch, patch.object(
            project_profile, "summarize_material", AsyncMock(return_value="프로필 문장")
        ), patch.object(project_profile.time, "monotonic", return_value=0.0):
            await project_profile.get_project_profile("p1")
            await project_profile.get_project_profile("p2")
            await project_profile.get_project_profile("p1")

        self.assertEqual(mock_fetch.await_count, 2)

    async def test_fetch_exception_returns_empty_and_is_not_cached(self):
        with patch.object(
            project_profile, "fetch_profile_material", AsyncMock(side_effect=RuntimeError("boom"))
        ) as mock_fetch, patch.object(
            project_profile, "summarize_material", AsyncMock()
        ) as mock_summarize, patch.object(project_profile.time, "monotonic", return_value=0.0):
            first = await project_profile.get_project_profile("p1")
            second = await project_profile.get_project_profile("p1")

        self.assertEqual(first, "")
        self.assertEqual(second, "")
        self.assertEqual(mock_fetch.await_count, 2)  # 캐시되지 않아 매번 다시 fetch
        mock_summarize.assert_not_called()

    async def test_empty_ttl_env_does_not_raise_and_uses_default(self):
        with patch.dict(os.environ, {"PROJECT_PROFILE_TTL_SECONDS": ""}), patch.object(
            project_profile, "fetch_profile_material", AsyncMock(return_value=_material())
        ) as mock_fetch, patch.object(
            project_profile, "summarize_material", AsyncMock(return_value="프로필 문장")
        ), patch.object(project_profile.time, "monotonic", return_value=0.0):
            first = await project_profile.get_project_profile("p1")
            second = await project_profile.get_project_profile("p1")

        self.assertEqual(first, "프로필 문장")
        self.assertEqual(second, "프로필 문장")
        mock_fetch.assert_awaited_once()  # 기본 TTL(24시간)로 캐시되어 재fetch 없음

    async def test_non_numeric_ttl_env_does_not_raise_and_uses_default(self):
        with patch.dict(os.environ, {"PROJECT_PROFILE_TTL_SECONDS": "abc"}), patch.object(
            project_profile, "fetch_profile_material", AsyncMock(return_value=_material())
        ) as mock_fetch, patch.object(
            project_profile, "summarize_material", AsyncMock(return_value="프로필 문장")
        ), patch.object(project_profile.time, "monotonic", return_value=0.0):
            first = await project_profile.get_project_profile("p1")
            second = await project_profile.get_project_profile("p1")

        self.assertEqual(first, "프로필 문장")
        self.assertEqual(second, "프로필 문장")
        mock_fetch.assert_awaited_once()  # 기본 TTL(24시간)로 캐시되어 재fetch 없음

    async def test_empty_profile_uses_short_ttl(self):
        with patch.object(
            project_profile, "fetch_profile_material", AsyncMock(return_value=_material())
        ) as mock_fetch, patch.object(
            project_profile, "summarize_material", AsyncMock(return_value="")
        ), patch.object(
            project_profile.time, "monotonic", side_effect=[0.0, 1000.0, 3601.0]
        ):
            first = await project_profile.get_project_profile("p1")  # t=0, fetch
            second = await project_profile.get_project_profile("p1")  # t=1000, cache (1000 < 3600)
            third = await project_profile.get_project_profile("p1")  # t=3601, expired, refetch

        self.assertEqual(first, "")
        self.assertEqual(second, "")
        self.assertEqual(third, "")
        self.assertEqual(mock_fetch.await_count, 2)

    async def test_non_empty_profile_keeps_long_ttl_at_same_times(self):
        with patch.object(
            project_profile, "fetch_profile_material", AsyncMock(return_value=_material())
        ) as mock_fetch, patch.object(
            project_profile, "summarize_material", AsyncMock(return_value="문장")
        ), patch.object(
            project_profile.time, "monotonic", side_effect=[0.0, 1000.0, 3601.0]
        ):
            await project_profile.get_project_profile("p1")
            await project_profile.get_project_profile("p1")
            await project_profile.get_project_profile("p1")

        self.assertEqual(mock_fetch.await_count, 1)  # 기본 TTL(24시간)이라 세 번 다 캐시


if __name__ == "__main__":
    unittest.main()
