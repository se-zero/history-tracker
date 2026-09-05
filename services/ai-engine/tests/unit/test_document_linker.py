"""Document 시맨틱 링크 빌더 단위 테스트 (오프라인 — DocumentLinkStore mock 주입).

다른 Layer 4 빌더와 윈도우·컷 방향이 다르다는 게 이 모듈의 핵심이라 그 두 가지를 집중 검증한다.

시간 윈도우: 문서는 오래 산다 — 하한(document.createdAt - pre_days)만 있고 상한이 없다.
컷 방향: 문서당 top-k(반대편인 ChangeSet/Issue는 열어 둔다) — TRIGGERED_BY의 "커밋당 top-1"과
정반대 방향이다. 매칭은 섹션 단위지만 집계·컷·엣지는 전부 문서 단위다.
"""

import math
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, patch

from graph.document_linker import (
    DESCRIBED_IN_THRESHOLD,
    DOCUMENT_PRE_BUFFER_DAYS,
    DOCUMENT_REFERENCE_THRESHOLD,
    DOCUMENT_TOP_K,
    DocumentLinkStore,
    build_described_in_document_edges,
    build_document_reference_edges,
    select_described_in_pairs,
    select_document_reference_pairs,
)
from routers.admin import DocumentLinkOptions, trigger_document_links

NOW = datetime(2026, 8, 1, tzinfo=timezone.utc)


def _vec(sim: float) -> list[float]:
    """기준 벡터 [1,0]과의 코사인 유사도가 정확히 sim인 단위 벡터."""
    return [sim, math.sqrt(1.0 - sim * sim)]


def _document(doc_id, created_at=NOW, project_id="p1"):
    return {"project_id": project_id, "id": doc_id, "created_at": created_at}


def _section(document_id, embedding, heading_path="섹션", project_id="p1"):
    return {
        "project_id": project_id,
        "document_id": document_id,
        "heading_path": heading_path,
        "embedding": embedding,
    }


def _changeset(changeset_id, embedding, occurred_at=NOW, project_id="p1"):
    return {"project_id": project_id, "changeset_id": changeset_id, "embedding": embedding, "occurred_at": occurred_at}


def _issue(issue_id, embedding, occurred_at=NOW, project_id="p1"):
    return {"project_id": project_id, "id": issue_id, "embedding": embedding, "occurred_at": occurred_at}


class SelectDocumentReferencePairsTest(unittest.TestCase):
    def test_above_threshold_kept_below_dropped(self):
        documents = [_document("NOTION:d1")]
        sections = [_section("NOTION:d1", _vec(1.0))]
        changesets = [
            _changeset("above", _vec(0.9)),
            _changeset("below", _vec(0.1)),
        ]

        pairs = select_document_reference_pairs(documents, sections, changesets, threshold=0.5)

        ids = {p[1] for p in pairs}
        self.assertIn("above", ids)
        self.assertNotIn("below", ids)

    def test_matches_section_but_reports_document_and_best_section(self):
        documents = [_document("NOTION:d1")]
        sections = [
            _section("NOTION:d1", _vec(0.5), heading_path="서론"),
            _section("NOTION:d1", _vec(0.95), heading_path="토큰 갱신"),
        ]
        changesets = [_changeset("c1", _vec(1.0))]

        pairs = select_document_reference_pairs(documents, sections, changesets, threshold=0.4)

        self.assertEqual(len(pairs), 1)
        project_id, changeset_id, document_id, score, section = pairs[0]
        self.assertEqual(document_id, "NOTION:d1")
        self.assertEqual(section, "토큰 갱신")
        self.assertAlmostEqual(score, 0.95, places=5)

    def test_before_document_created_minus_buffer_excluded(self):
        document_created = NOW
        documents = [_document("NOTION:d1", created_at=document_created)]
        sections = [_section("NOTION:d1", _vec(1.0))]
        too_early = document_created - timedelta(days=DOCUMENT_PRE_BUFFER_DAYS, hours=1)
        changesets = [_changeset("too_early", _vec(1.0), occurred_at=too_early)]

        pairs = select_document_reference_pairs(documents, sections, changesets, threshold=0.5)

        self.assertEqual(pairs, [])

    def test_within_pre_buffer_included(self):
        document_created = NOW
        documents = [_document("NOTION:d1", created_at=document_created)]
        sections = [_section("NOTION:d1", _vec(1.0))]
        within_buffer = document_created - timedelta(days=DOCUMENT_PRE_BUFFER_DAYS - 1)
        changesets = [_changeset("within", _vec(1.0), occurred_at=within_buffer)]

        pairs = select_document_reference_pairs(documents, sections, changesets, threshold=0.5)

        self.assertEqual(len(pairs), 1)
        self.assertEqual(pairs[0][1], "within")

    def test_no_upper_bound_far_future_commit_still_included(self):
        # 문서는 오래 산다 — TRIGGERED_BY/DISCUSSED_IN과 달리 상한이 없다.
        documents = [_document("NOTION:d1", created_at=NOW)]
        sections = [_section("NOTION:d1", _vec(1.0))]
        far_future = NOW + timedelta(days=365)
        changesets = [_changeset("later", _vec(1.0), occurred_at=far_future)]

        pairs = select_document_reference_pairs(documents, sections, changesets, threshold=0.5)

        self.assertEqual(len(pairs), 1)
        self.assertEqual(pairs[0][1], "later")

    def test_top_k_per_document_cuts_excess_candidates_but_leaves_source_side_open(self):
        documents = [_document("NOTION:d1")]
        sections = [_section("NOTION:d1", _vec(1.0))]
        # top_k=2인데 후보 3개 — 가장 낮은 점수 하나가 잘려야 한다.
        changesets = [
            _changeset("best", _vec(0.99)),
            _changeset("mid", _vec(0.9)),
            _changeset("worst", _vec(0.6)),
        ]

        pairs = select_document_reference_pairs(documents, sections, changesets, threshold=0.5, top_k=2)

        ids = {p[1] for p in pairs}
        self.assertEqual(ids, {"best", "mid"})

    def test_different_projects_do_not_cross_link(self):
        documents = [_document("NOTION:d1", project_id="p1"), _document("NOTION:d2", project_id="p2")]
        sections = [
            _section("NOTION:d1", _vec(1.0), project_id="p1"),
            _section("NOTION:d2", _vec(1.0), project_id="p2"),
        ]
        changesets = [_changeset("c1", _vec(1.0), project_id="p1")]

        pairs = select_document_reference_pairs(documents, sections, changesets, threshold=0.5)

        # p1의 커밋이 p2 문서와는 애초에 비교조차 되지 않는다(그룹핑 단계에서 분리).
        self.assertEqual(len(pairs), 1)
        self.assertEqual(pairs[0][2], "NOTION:d1")

    def test_empty_embedding_excluded(self):
        documents = [_document("NOTION:d1")]
        sections = [_section("NOTION:d1", [])]
        changesets = [_changeset("c1", _vec(1.0))]

        pairs = select_document_reference_pairs(documents, sections, changesets, threshold=0.1)

        self.assertEqual(pairs, [])

    def test_default_thresholds_and_top_k_are_module_constants(self):
        # 회귀 방지 — 상수가 바뀌면 여기서 드러난다(값 자체의 근거는 모듈 docstring 참고).
        self.assertEqual(DOCUMENT_REFERENCE_THRESHOLD, 0.44)
        self.assertEqual(DESCRIBED_IN_THRESHOLD, 0.48)
        self.assertEqual(DOCUMENT_TOP_K, 5)
        self.assertEqual(DOCUMENT_PRE_BUFFER_DAYS, 7)


class SelectDescribedInPairsTest(unittest.TestCase):
    def test_uses_issue_id_key_and_described_in_threshold(self):
        documents = [_document("NOTION:d1")]
        sections = [_section("NOTION:d1", _vec(1.0))]
        issues = [_issue("JIRA:HT-1", _vec(0.9))]

        pairs = select_described_in_pairs(documents, sections, issues, threshold=0.5)

        self.assertEqual(len(pairs), 1)
        self.assertEqual(pairs[0][1], "JIRA:HT-1")
        self.assertEqual(pairs[0][2], "NOTION:d1")


class BuildDocumentReferenceEdgesTest(unittest.IsolatedAsyncioTestCase):
    async def test_creates_edge_for_each_selected_pair(self):
        created: list[tuple] = []

        async def fetch_documents():
            return [_document("NOTION:d1")]

        async def fetch_sections():
            return [_section("NOTION:d1", _vec(1.0), heading_path="설계")]

        async def fetch_modified():
            return [_changeset("c1", _vec(0.9))]

        async def create_reference(project_id, changeset_id, document_id, confidence, section):
            created.append((project_id, changeset_id, document_id, confidence, section))

        async def unsupported(*args, **kwargs):
            raise AssertionError("호출되면 안 되는 콜백")

        store = DocumentLinkStore(
            fetch_documents=fetch_documents,
            fetch_document_sections=fetch_sections,
            fetch_modified_embeddings=fetch_modified,
            fetch_issue_embeddings=unsupported,
            create_reference_edge=create_reference,
            create_described_in_edge=unsupported,
        )

        result = await build_document_reference_edges(store, threshold=0.5)

        self.assertEqual(result, 1)
        self.assertEqual(len(created), 1)
        self.assertEqual(created[0][:3], ("p1", "c1", "NOTION:d1"))
        self.assertEqual(created[0][4], "설계")

    async def test_no_documents_skips_without_calling_create(self):
        async def empty():
            return []

        async def unsupported(*args, **kwargs):
            raise AssertionError("호출되면 안 되는 콜백")

        store = DocumentLinkStore(
            fetch_documents=empty,
            fetch_document_sections=empty,
            fetch_modified_embeddings=empty,
            fetch_issue_embeddings=unsupported,
            create_reference_edge=unsupported,
            create_described_in_edge=unsupported,
        )

        result = await build_document_reference_edges(store)

        self.assertEqual(result, 0)


class BuildDescribedInDocumentEdgesTest(unittest.IsolatedAsyncioTestCase):
    async def test_creates_edge_for_each_selected_pair(self):
        created: list[tuple] = []

        async def fetch_documents():
            return [_document("NOTION:d1")]

        async def fetch_sections():
            return [_section("NOTION:d1", _vec(1.0), heading_path="배경")]

        async def fetch_issues():
            return [_issue("JIRA:HT-1", _vec(0.9))]

        async def create_described_in(project_id, issue_id, document_id, confidence, section):
            created.append((project_id, issue_id, document_id, confidence, section))

        async def unsupported(*args, **kwargs):
            raise AssertionError("호출되면 안 되는 콜백")

        store = DocumentLinkStore(
            fetch_documents=fetch_documents,
            fetch_document_sections=fetch_sections,
            fetch_modified_embeddings=unsupported,
            fetch_issue_embeddings=fetch_issues,
            create_reference_edge=unsupported,
            create_described_in_edge=create_described_in,
        )

        result = await build_described_in_document_edges(store, threshold=0.5)

        self.assertEqual(result, 1)
        self.assertEqual(created[0][:3], ("p1", "JIRA:HT-1", "NOTION:d1"))
        self.assertEqual(created[0][4], "배경")


class TriggerDocumentLinksRouteTest(unittest.IsolatedAsyncioTestCase):
    """POST /document-links/build 라우터 핸들러 — 옵션이 빌더까지 그대로 전달되는지 검증.

    routers.admin.trigger_document_links는 이름을 직접 들여와 바인딩하므로(graph.document_linker
    쪽 원본이 아니라) routers.admin의 참조를 patch해야 라우터가 호출하는 대상이 바뀐다
    (tests/unit/test_privacy_accounts.py의 관례와 동일).
    """

    async def test_default_options_use_module_constants(self):
        with (
            patch("routers.admin.make_neo4j_document_link_store", return_value="store") as make_store,
            patch("routers.admin.build_document_reference_edges", AsyncMock(return_value=3)) as build_reference,
            patch("routers.admin.build_described_in_document_edges", AsyncMock(return_value=2)) as build_described_in,
        ):
            result = await trigger_document_links()

        make_store.assert_called_once_with()
        build_reference.assert_awaited_once_with(
            "store", threshold=DOCUMENT_REFERENCE_THRESHOLD, top_k=DOCUMENT_TOP_K, pre_days=DOCUMENT_PRE_BUFFER_DAYS
        )
        build_described_in.assert_awaited_once_with(
            "store", threshold=DESCRIBED_IN_THRESHOLD, top_k=DOCUMENT_TOP_K, pre_days=DOCUMENT_PRE_BUFFER_DAYS
        )
        self.assertEqual(result, {"reference": 3, "described_in": 2})

    async def test_explicit_options_are_passed_through(self):
        options = DocumentLinkOptions(reference_threshold=0.6, described_in_threshold=0.7, top_k=2, pre_days=14)

        with (
            patch("routers.admin.make_neo4j_document_link_store", return_value="store"),
            patch("routers.admin.build_document_reference_edges", AsyncMock(return_value=0)) as build_reference,
            patch("routers.admin.build_described_in_document_edges", AsyncMock(return_value=0)) as build_described_in,
        ):
            await trigger_document_links(options)

        build_reference.assert_awaited_once_with("store", threshold=0.6, top_k=2, pre_days=14)
        build_described_in.assert_awaited_once_with("store", threshold=0.7, top_k=2, pre_days=14)


if __name__ == "__main__":
    unittest.main()
