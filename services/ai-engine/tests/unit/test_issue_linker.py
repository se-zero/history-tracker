"""이슈 시맨틱 링크 빌더 단위 테스트 (오프라인 — IssueLinkStore mock 주입).

DISCUSSED_IN의 두 가지 컷을 검증한다.

fan-out 컷: 이슈의 논의 스레드 수는 팀 활동량에 비례하므로 "이슈당 n개" 같은 개수
가정을 박을 수 없다. 대신 **이슈의 최고점 스레드와 점수가 비슷한 것만 유지**하는 상대
마진으로 자른다 — 진짜 논의가 2개든 10개든 최고점 근처에 모이면 전부 살아남는다.

시간 윈도우: 이슈의 **생애**(생성~종료)를 기준으로 자른다. 이전의 "최종 수정 시각 ±30일"
대칭 윈도우는 앵커가 틀려서 오래 걸린 이슈의 초반 논의를 놓치고, 반대로 이슈가 끝난 뒤
한 달간의 무관한 대화를 전부 후보로 삼았다.
"""

import asyncio
import math
import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock

from graph.issue_linker import (
    DEFAULT_DISCUSSED_IN_MARGIN,
    DISCUSSED_IN_POST_BUFFER_DAYS,
    DISCUSSED_IN_PRE_BUFFER_DAYS,
    TRIGGERED_BY_MESSAGE_MODE,
    TRIGGERED_BY_THRESHOLD,
    IssueLinkStore,
    backfill_issue_embeddings,
    build_issue_changeset_links,
    build_issue_communication_links,
)

NOW = datetime(2026, 7, 1, tzinfo=timezone.utc)


def _vec(sim: float) -> list[float]:
    """기준 벡터 [1,0]과의 코사인 유사도가 정확히 sim인 단위 벡터."""
    return [sim, math.sqrt(1.0 - sim * sim)]


_UNSET = object()   # None("필드 없음")과 "기본값 쓰기"를 구분하기 위한 센티널


def _issue(jira_key, embedding=None, project_id="p1", occurred_at=NOW,
           created_at=_UNSET, closed_at=_UNSET, status="완료"):
    """기본값은 NOW에 생성돼 NOW에 종료된 이슈 — 윈도우가 wall-clock에 의존하지 않게 한다.

    (status가 terminal이 아니고 closed_at도 없으면 '진행 중'으로 간주돼 윈도우 상한이
    now()가 되고, 그러면 테스트 결과가 실행 시각에 따라 달라진다.)
    created_at/closed_at에 None을 명시하면 그 필드가 실제로 비어 있는 이슈가 된다.
    """
    return {
        "project_id": project_id,
        "id": jira_key,
        "embedding": embedding or [1.0, 0.0],
        "occurred_at": occurred_at,
        "created_at": occurred_at if created_at is _UNSET else created_at,
        "closed_at": occurred_at if closed_at is _UNSET else closed_at,
        "status": status,
    }


def _comm(comm_id, embedding, project_id="p1", occurred_at=NOW, conversation_id=None):
    return {
        "project_id": project_id,
        "id": comm_id,
        "conversation_id": conversation_id or comm_id,   # 기본값: 자기 자신이 스레드
        "embedding": embedding,
        "occurred_at": occurred_at,
    }


class _FakeStore:
    """IssueLinkStore 콜백 대역 — create_discussed_in_edge 호출을 그대로 기록한다."""

    def __init__(self, issues, comms):
        self.issues = issues
        self.comms = comms
        self.created: list[tuple[str, str, str, float]] = []

    def as_store(self) -> IssueLinkStore:
        async def fetch_issues():
            return self.issues

        async def fetch_comms():
            return self.comms

        async def create_discussed_in(project_id, jira_key, comm_id, confidence):
            self.created.append((project_id, jira_key, comm_id, confidence))

        async def unsupported(*args, **kwargs):
            raise AssertionError("이 테스트에서 호출되지 않아야 한다")

        return IssueLinkStore(
            fetch_issue_embeddings=fetch_issues,
            fetch_modified_embeddings=unsupported,
            fetch_communication_embeddings=fetch_comms,
            create_triggered_by_edge=unsupported,
            create_discussed_in_edge=create_discussed_in,
            fetch_changeset_message_embeddings=unsupported,
            fetch_unembedded_issues=unsupported,
            save_issue_embedding=unsupported,
        )

    def linked_comms(self) -> set[str]:
        return {comm_id for _, _, comm_id, _ in self.created}


class DiscussedInMarginTest(unittest.TestCase):
    """이슈별 최고점 − margin 안에 드는 스레드만 유지."""

    def test_keeps_only_threads_within_margin_of_best(self):
        # 최고점 0.90, margin 0.10 → 0.80 이상인 스레드만 생존 (0.79·0.50은 컷)
        fake = _FakeStore(
            issues=[_issue("HT-1")],
            comms=[
                _comm("m1", _vec(0.90), conversation_id="t1"),
                _comm("m2", _vec(0.85), conversation_id="t2"),
                _comm("m3", _vec(0.79), conversation_id="t3"),
                _comm("m4", _vec(0.50), conversation_id="t4"),
            ],
        )

        created = asyncio.run(
            build_issue_communication_links(fake.as_store(), threshold=0.4, margin=0.10)
        )

        self.assertEqual(created, 2)
        self.assertEqual(fake.linked_comms(), {"m1", "m2"})

    def test_margin_uses_best_message_in_thread(self):
        # 스레드의 대표값은 최고점 — t2는 최고 메시지(0.88)로 살아남고, 저점 메시지도 함께 남는다
        fake = _FakeStore(
            issues=[_issue("HT-1")],
            comms=[
                _comm("m1", _vec(0.90), conversation_id="t1"),
                _comm("m2a", _vec(0.45), conversation_id="t2"),
                _comm("m2b", _vec(0.88), conversation_id="t2"),
            ],
        )

        created = asyncio.run(
            build_issue_communication_links(fake.as_store(), threshold=0.4, margin=0.10)
        )

        self.assertEqual(created, 3)
        self.assertEqual(fake.linked_comms(), {"m1", "m2a", "m2b"})

    def test_margin_is_computed_per_issue(self):
        # 이슈마다 자기 최고점 기준 — HT-2의 최고점이 낮다고 HT-1의 컷이 내려가지 않는다
        fake = _FakeStore(
            issues=[_issue("HT-1", _vec(1.0)), _issue("HT-2", _vec(0.5))],
            comms=[
                _comm("m1", _vec(0.90), conversation_id="t1"),
                _comm("m2", _vec(0.60), conversation_id="t2"),
            ],
        )

        asyncio.run(build_issue_communication_links(fake.as_store(), threshold=0.4, margin=0.10))

        linked = {(jira_key, comm_id) for _, jira_key, comm_id, _ in fake.created}
        self.assertIn(("HT-1", "m1"), linked)        # HT-1의 최고점 스레드
        self.assertNotIn(("HT-1", "m2"), linked)     # 최고점과 0.30 차 — 마진 밖
        self.assertIn(("HT-2", "m2"), linked)        # HT-2 기준으로는 이쪽이 최고점

    def test_default_margin_is_applied(self):
        # 파라미터를 안 주면 DEFAULT_DISCUSSED_IN_MARGIN(=0.10)이 적용된다 — 무제한이 아니다
        fake = _FakeStore(
            issues=[_issue("HT-1")],
            comms=[
                _comm("m1", _vec(0.90), conversation_id="t1"),
                _comm("m2", _vec(0.60), conversation_id="t2"),
            ],
        )

        created = asyncio.run(build_issue_communication_links(fake.as_store(), threshold=0.4))

        self.assertEqual(DEFAULT_DISCUSSED_IN_MARGIN, 0.10)
        self.assertEqual(created, 1)
        self.assertEqual(fake.linked_comms(), {"m1"})

    def test_missing_conversation_id_falls_back_to_message_as_own_thread(self):
        fake = _FakeStore(
            issues=[_issue("HT-1")],
            comms=[
                {**_comm("m1", _vec(0.90)), "conversation_id": None},
                {**_comm("m2", _vec(0.60)), "conversation_id": None},
            ],
        )

        created = asyncio.run(
            build_issue_communication_links(fake.as_store(), threshold=0.4, margin=0.10)
        )

        self.assertEqual(created, 1)
        self.assertEqual(fake.linked_comms(), {"m1"})


class DiscussedInFilterRegressionTest(unittest.TestCase):
    """마진 컷이 임계값·시간 윈도우·프로젝트 격리를 깨지 않았는지."""

    def test_below_threshold_is_skipped(self):
        # 마진 안에 들어도 바닥 임계값을 못 넘으면 엣지가 없다 (마진은 바닥선을 대체하지 않는다)
        fake = _FakeStore(
            issues=[_issue("HT-1")],
            comms=[_comm("m1", _vec(0.35), conversation_id="t1")],
        )

        created = asyncio.run(
            build_issue_communication_links(fake.as_store(), threshold=0.4, margin=0.10)
        )

        self.assertEqual(created, 0)

    def test_outside_time_window_is_skipped(self):
        # 이슈 종료 후 post 버퍼를 한참 넘긴 메시지 — 점수가 아무리 높아도 후보가 아니다
        fake = _FakeStore(
            issues=[_issue("HT-1", created_at=NOW, closed_at=NOW)],
            comms=[_comm("m1", _vec(0.90), conversation_id="t1",
                         occurred_at=NOW + timedelta(days=31))],
        )

        created = asyncio.run(
            build_issue_communication_links(fake.as_store(), threshold=0.4, margin=0.10)
        )

        self.assertEqual(created, 0)

    def test_cross_project_pair_is_not_linked(self):
        fake = _FakeStore(
            issues=[_issue("HT-1", project_id="p1")],
            comms=[_comm("m1", _vec(0.90), project_id="p2", conversation_id="t1")],
        )

        created = asyncio.run(
            build_issue_communication_links(fake.as_store(), threshold=0.4, margin=0.10)
        )

        self.assertEqual(created, 0)


class DiscussedInIssueLifetimeWindowTest(unittest.TestCase):
    """후보 시간 범위는 이슈 생애 [created − pre, closed + post] 다."""

    def _run(self, fake, **kw):
        return asyncio.run(
            build_issue_communication_links(fake.as_store(), threshold=0.4, margin=0.10, **kw)
        )

    def test_long_running_issue_includes_early_discussion(self):
        # 핵심 케이스: 4/1 생성 → 6/30 종료된 이슈의 4/2 논의.
        # 최종 수정(6/30) 기준 ±30일 대칭 윈도우로는 89일 전이라 잘렸다. 생애 기준이면 산다.
        created_at = datetime(2026, 4, 1, tzinfo=timezone.utc)
        closed_at = datetime(2026, 6, 30, tzinfo=timezone.utc)
        fake = _FakeStore(
            issues=[_issue("HT-1", occurred_at=closed_at,
                           created_at=created_at, closed_at=closed_at)],
            comms=[_comm("m1", _vec(0.90), conversation_id="t1",
                         occurred_at=created_at + timedelta(days=1))],
        )

        created = self._run(fake, pre_days=3, post_days=3)

        self.assertEqual(created, 1)
        self.assertEqual(fake.linked_comms(), {"m1"})

    def test_message_after_close_beyond_post_buffer_is_skipped(self):
        closed_at = NOW
        fake = _FakeStore(
            issues=[_issue("HT-1", created_at=NOW, closed_at=closed_at)],
            comms=[
                _comm("in", _vec(0.90), conversation_id="t1",
                      occurred_at=closed_at + timedelta(days=2)),   # post=3 안
                _comm("out", _vec(0.90), conversation_id="t2",
                      occurred_at=closed_at + timedelta(days=4)),   # post=3 밖
            ],
        )

        self._run(fake, pre_days=3, post_days=3)

        self.assertEqual(fake.linked_comms(), {"in"})

    def test_message_before_creation_beyond_pre_buffer_is_skipped(self):
        created_at = NOW
        fake = _FakeStore(
            issues=[_issue("HT-1", created_at=created_at, closed_at=NOW)],
            comms=[
                _comm("in", _vec(0.90), conversation_id="t1",
                      occurred_at=created_at - timedelta(days=2)),   # pre=3 안 (이슈 파기 전 논의)
                _comm("out", _vec(0.90), conversation_id="t2",
                      occurred_at=created_at - timedelta(days=4)),   # pre=3 밖
            ],
        )

        self._run(fake, pre_days=3, post_days=3)

        self.assertEqual(fake.linked_comms(), {"in"})

    def test_open_issue_has_no_upper_bound(self):
        # 진행 중인 이슈는 상한이 now — 생성 후 한참 뒤(±30일 밖)의 논의도 후보다
        created_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
        fake = _FakeStore(
            issues=[_issue("HT-1", occurred_at=created_at, created_at=created_at,
                           closed_at=None, status="진행 중")],
            comms=[_comm("m1", _vec(0.90), conversation_id="t1",
                         occurred_at=created_at + timedelta(days=90))],
        )

        created = self._run(fake, pre_days=3, post_days=3)

        self.assertEqual(created, 1)

    def test_created_at_missing_falls_back_to_occurred_at(self):
        # pipeline-worker가 createdAt을 안 보낸 이슈 — occurred_at을 생성 시각으로 본다
        fake = _FakeStore(
            issues=[_issue("HT-1", created_at=None, closed_at=NOW)],
            comms=[
                _comm("in", _vec(0.90), conversation_id="t1",
                      occurred_at=NOW - timedelta(days=2)),
                _comm("out", _vec(0.90), conversation_id="t2",
                      occurred_at=NOW - timedelta(days=4)),
            ],
        )

        self._run(fake, pre_days=3, post_days=3)

        self.assertEqual(fake.linked_comms(), {"in"})

    def test_default_buffers_are_discussed_in_specific(self):
        # 인자를 안 주면 DISCUSSED_IN 전용 버퍼가 적용된다 (TRIGGERED_BY의 1/3을 재사용하지 않는다).
        # 대화는 커밋과 달리 이슈 생성 전 논의가 흔해서 pre가 더 넓다.
        self.assertEqual(DISCUSSED_IN_PRE_BUFFER_DAYS, 4)
        self.assertEqual(DISCUSSED_IN_POST_BUFFER_DAYS, 3)

        fake = _FakeStore(
            issues=[_issue("HT-1", created_at=NOW, closed_at=NOW)],
            comms=[
                _comm("in", _vec(0.90), conversation_id="t1",
                      occurred_at=NOW - timedelta(days=DISCUSSED_IN_PRE_BUFFER_DAYS)),
                _comm("out", _vec(0.90), conversation_id="t2",
                      occurred_at=NOW - timedelta(days=DISCUSSED_IN_PRE_BUFFER_DAYS + 1)),
            ],
        )

        asyncio.run(build_issue_communication_links(fake.as_store(), threshold=0.4, margin=0.10))

        self.assertEqual(fake.linked_comms(), {"in"})


class TriggeredByDefaultThresholdTest(unittest.TestCase):
    """TRIGGERED_BY 기본 임계값 — 무링크 커밋 풀 전용의 낮은 값."""

    @staticmethod
    def _mod(cs_id, embedding, occurred_at=NOW, project_id="p1"):
        return {
            "project_id": project_id,
            "changeset_id": cs_id,
            "file_path": "src/a.py",
            "diff_summary": "",
            "embedding": embedding,
            "occurred_at": occurred_at,
        }

    def test_default_threshold_catches_low_scoring_true_links(self):
        # 후보는 text 링크가 전혀 없는 커밋뿐(store fetch에서 제외)이라 풀이 작고,
        # 진짜 연결은 diff 요약↔이슈의 어휘 차이로 낮은 점수대에 깔려 있다 — 0.55는 전멸시켰다.
        # 값은 임베딩 모델(3-large@1536) 재스윕으로 확정 — 모델을 바꾸면 함께 재스윕된다.
        self.assertEqual(TRIGGERED_BY_THRESHOLD, 0.34)

        created: list[tuple[str, str, str, float]] = []

        async def fetch_issues():
            return [_issue("HT-6")]

        async def fetch_mods():
            return [
                self._mod("cs-golden", _vec(0.36)),   # 골든 쌍 점수대 — 기본값에서 살아야 한다
                self._mod("cs-below", _vec(0.33)),    # 바닥 아래 — 잘려야 한다
            ]

        async def create_tb(project_id, cs_id, jira_key, confidence):
            created.append((project_id, cs_id, jira_key, confidence))

        async def unsupported(*args, **kwargs):
            raise AssertionError("이 테스트에서 호출되지 않아야 한다")

        async def fetch_no_messages():
            # 기본 모드(max)가 메시지 열도 fetch하지만, 이 테스트는 diff 행의 임계값만 본다
            return []

        store = IssueLinkStore(
            fetch_issue_embeddings=fetch_issues,
            fetch_modified_embeddings=fetch_mods,
            fetch_communication_embeddings=unsupported,
            create_triggered_by_edge=create_tb,
            create_discussed_in_edge=unsupported,
            fetch_changeset_message_embeddings=fetch_no_messages,
            fetch_unembedded_issues=unsupported,
            save_issue_embedding=unsupported,
        )

        asyncio.run(build_issue_changeset_links(store))

        self.assertEqual([(cs, key) for _, cs, key, _ in created],
                         [("cs-golden", "HT-6")])


def _message_row(changeset_id, embedding, project_id="p1", occurred_at=NOW):
    """커밋 메시지 임베딩 행 — 파일(diff) 행과 달리 커밋당 1개다."""
    return {
        "project_id": project_id,
        "changeset_id": changeset_id,
        "embedding": embedding,
        "occurred_at": occurred_at,
    }


class _FakeTbStore:
    """TRIGGERED_BY용 IssueLinkStore 대역 — create_triggered_by_edge 호출을 기록한다."""

    def __init__(self, issues, mods, messages=None):
        self.issues = issues
        self.mods = mods
        self.messages = messages or []
        self.created: list[tuple[str, str, str, float]] = []
        self.fetched_mods = False
        self.fetched_messages = False

    def as_store(self) -> IssueLinkStore:
        async def fetch_issues():
            return self.issues

        async def fetch_mods():
            self.fetched_mods = True
            return self.mods

        async def fetch_messages():
            self.fetched_messages = True
            return self.messages

        async def create_tb(project_id, cs_id, jira_key, confidence):
            self.created.append((project_id, cs_id, jira_key, confidence))

        async def unsupported(*args, **kwargs):
            raise AssertionError("이 테스트에서 호출되지 않아야 한다")

        return IssueLinkStore(
            fetch_issue_embeddings=fetch_issues,
            fetch_modified_embeddings=fetch_mods,
            fetch_communication_embeddings=unsupported,
            create_triggered_by_edge=create_tb,
            create_discussed_in_edge=unsupported,
            fetch_changeset_message_embeddings=fetch_messages,
            fetch_unembedded_issues=unsupported,
            save_issue_embedding=unsupported,
        )


class TriggeredByMessageModeTest(unittest.TestCase):
    """TRIGGERED_BY의 message_mode — 커밋 메시지 임베딩을 비교 열에 넣는 두 설계(max/only)와 현행(off)."""

    @staticmethod
    def _mod(cs_id, embedding, occurred_at=NOW, project_id="p1"):
        return {
            "project_id": project_id,
            "changeset_id": cs_id,
            "file_path": "src/a.py",
            "diff_summary": "",
            "embedding": embedding,
            "occurred_at": occurred_at,
        }

    def test_off_mode_does_not_fetch_message_embeddings(self):
        # off는 메시지 임베딩 도입 전 동작 그대로 — 조회조차 하지 않는다 (측정 재현용)
        fake = _FakeTbStore(
            issues=[_issue("HT-1")],
            mods=[self._mod("c1", _vec(0.9))],
            messages=[_message_row("c1", _vec(0.9))],
        )

        created = asyncio.run(
            build_issue_changeset_links(fake.as_store(), threshold=0.3, message_mode="off")
        )

        self.assertFalse(fake.fetched_messages)
        self.assertEqual(created, 1)

    def test_max_mode_merges_message_and_diff_into_single_top1(self):
        # 같은 커밋의 diff 열(0.5)과 메시지 열(0.9)이 top-1 집계에서 같은 키로 병합 —
        # 엣지는 1개, confidence는 둘 중 최고점
        fake = _FakeTbStore(
            issues=[_issue("HT-1")],
            mods=[self._mod("c1", _vec(0.5))],
            messages=[_message_row("c1", _vec(0.9))],
        )

        created = asyncio.run(
            build_issue_changeset_links(fake.as_store(), threshold=0.3, message_mode="max")
        )

        self.assertEqual(created, 1)
        self.assertEqual(fake.created[0][1], "c1")
        self.assertAlmostEqual(fake.created[0][3], 0.9, places=5)

    def test_max_mode_covers_commits_without_diff_embeddings(self):
        # MODIFIED 임베딩이 없는 커밋(파일 전부 스킵)도 메시지로는 후보가 된다 (후보 풀 확장)
        fake = _FakeTbStore(
            issues=[_issue("HT-1")],
            mods=[],
            messages=[_message_row("c1", _vec(0.9))],
        )

        created = asyncio.run(
            build_issue_changeset_links(fake.as_store(), threshold=0.3, message_mode="max")
        )

        self.assertEqual(created, 1)

    def test_only_mode_ignores_diff_embeddings(self):
        # diff가 아무리 높아도 only 모드에선 메시지 점수로만 판단 — diff는 fetch조차 하지 않는다
        fake = _FakeTbStore(
            issues=[_issue("HT-1")],
            mods=[self._mod("c1", _vec(0.95))],
            messages=[_message_row("c1", _vec(0.4))],
        )

        created = asyncio.run(
            build_issue_changeset_links(fake.as_store(), threshold=0.5, message_mode="only")
        )

        self.assertEqual(created, 0)
        self.assertFalse(fake.fetched_mods)

    def test_message_row_respects_issue_window(self):
        # 이슈 종료(+3d 유예) 후의 커밋은 메시지 점수가 높아도 후보가 아니다
        fake = _FakeTbStore(
            issues=[_issue("HT-1", created_at=NOW, closed_at=NOW)],
            mods=[],
            messages=[_message_row("c1", _vec(0.9), occurred_at=NOW + timedelta(days=10))],
        )

        created = asyncio.run(
            build_issue_changeset_links(fake.as_store(), threshold=0.3, message_mode="only")
        )

        self.assertEqual(created, 0)

    def test_invalid_mode_raises(self):
        fake = _FakeTbStore(issues=[], mods=[], messages=[])

        with self.assertRaises(ValueError):
            asyncio.run(build_issue_changeset_links(fake.as_store(), message_mode="diff"))


class TriggeredByAdoptedDefaultsTest(unittest.TestCase):
    """기본값 = 채택 구성 (message_mode max, 임계값 0.34).

    postprocess 자동 빌드는 인자 없이 호출하므로, 측정으로 확정한 구성과 코드 기본값이
    같아야 프로덕션이 측정과 동일하게 돈다 — 그 일치를 박는 계약 테스트.
    """

    @staticmethod
    def _mod(cs_id, embedding, occurred_at=NOW, project_id="p1"):
        return {
            "project_id": project_id,
            "changeset_id": cs_id,
            "file_path": "src/a.py",
            "diff_summary": "",
            "embedding": embedding,
            "occurred_at": occurred_at,
        }

    def test_default_message_mode_is_max(self):
        self.assertEqual(TRIGGERED_BY_MESSAGE_MODE, "max")

    def test_default_call_uses_message_rows(self):
        # diff(0.2)는 임계값 밑, 메시지(0.9)는 위 — 인자 없는 기본 호출이 메시지 열로 top-1을 만든다
        fake = _FakeTbStore(
            issues=[_issue("HT-1")],
            mods=[self._mod("c1", _vec(0.2))],
            messages=[_message_row("c1", _vec(0.9))],
        )

        created = asyncio.run(build_issue_changeset_links(fake.as_store()))

        self.assertTrue(fake.fetched_messages)
        self.assertEqual(created, 1)
        self.assertAlmostEqual(fake.created[0][3], 0.9, places=5)


class IssueEmbeddingBackfillTest(unittest.TestCase):
    """backfill_issue_embeddings — embedding이 없는 Issue 노드 보정.

    임베딩 대상 텍스트는 수집 경로(event_handler)와 같은 "title\\n\\nbody"여야 한다 —
    다르면 수집으로 들어온 이슈와 백필로 채운 이슈의 벡터가 서로 다른 기준이 된다.
    force는 이미 embedding이 있는 노드까지 덮어쓰는 전량 재임베딩(모델 교체용).
    반환은 {"saved", "total"} — saved != total이면 완주하지 못한 것이다.
    """

    def _store(self, nodes, seen=None):
        saved: list[tuple[str, str, list[float]]] = []

        async def fetch_unembedded(force):
            if seen is not None:
                seen.append(force)
            return nodes

        async def save(project_id, jira_key, embedding):
            saved.append((project_id, jira_key, embedding))

        async def unsupported(*args, **kwargs):
            raise AssertionError("이 테스트에서 호출되지 않아야 한다")

        store = IssueLinkStore(
            fetch_issue_embeddings=unsupported,
            fetch_modified_embeddings=unsupported,
            fetch_communication_embeddings=unsupported,
            create_triggered_by_edge=unsupported,
            create_discussed_in_edge=unsupported,
            fetch_changeset_message_embeddings=unsupported,
            fetch_unembedded_issues=fetch_unembedded,
            save_issue_embedding=save,
        )
        return store, saved

    def test_embeds_title_and_body_joined_like_ingestion(self):
        store, saved = self._store([
            {"project_id": "p1", "id": "HT-1", "title": "로그인 실패", "body": "토큰 만료 처리"},
            {"project_id": "p1", "id": "HT-2", "title": "문서 정리", "body": ""},
        ])

        with mock.patch(
            "graph.issue_linker.embed_batch",
            new=mock.AsyncMock(return_value=[[0.1, 0.2], [0.3, 0.4]]),
        ) as embed:
            result = asyncio.run(backfill_issue_embeddings(store))

        embed.assert_awaited_once_with(["로그인 실패\n\n토큰 만료 처리", "문서 정리\n\n"])
        self.assertEqual(result, {"saved": 2, "total": 2})
        self.assertEqual(saved, [("p1", "HT-1", [0.1, 0.2]), ("p1", "HT-2", [0.3, 0.4])])

    def test_partial_embedding_failure_shows_as_saved_below_total(self):
        # 신·구 모델 벡터가 같은 1536차원이라 섞여도 조용히 계산된다 —
        # 완주하지 못했다는 사실은 saved < total로만 드러난다.
        store, saved = self._store([
            {"project_id": "p1", "id": "HT-1", "title": "A", "body": "a"},
            {"project_id": "p1", "id": "HT-2", "title": "B", "body": "b"},
        ])

        with mock.patch(
            "graph.issue_linker.embed_batch",
            new=mock.AsyncMock(return_value=[[0.5, 0.5], []]),
        ):
            result = asyncio.run(backfill_issue_embeddings(store))

        self.assertEqual(result, {"saved": 1, "total": 2})
        self.assertEqual(saved, [("p1", "HT-1", [0.5, 0.5])])

    def test_noop_when_nothing_to_backfill(self):
        store, saved = self._store([])

        with mock.patch("graph.issue_linker.embed_batch", new=mock.AsyncMock()) as embed:
            result = asyncio.run(backfill_issue_embeddings(store))

        embed.assert_not_awaited()
        self.assertEqual(result, {"saved": 0, "total": 0})
        self.assertEqual(saved, [])

    def test_force_flag_is_forwarded_to_fetch(self):
        seen: list[bool] = []
        store, _ = self._store([], seen)

        asyncio.run(backfill_issue_embeddings(store))
        asyncio.run(backfill_issue_embeddings(store, force=True))

        self.assertEqual(seen, [False, True])   # 기본은 구멍 메우기, force면 전량


if __name__ == "__main__":
    unittest.main()
