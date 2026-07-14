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

from graph.issue_linker import (
    DEFAULT_DISCUSSED_IN_MARGIN,
    DISCUSSED_IN_POST_BUFFER_DAYS,
    DISCUSSED_IN_PRE_BUFFER_DAYS,
    IssueLinkStore,
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


if __name__ == "__main__":
    unittest.main()
