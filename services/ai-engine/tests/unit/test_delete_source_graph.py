"""연동 해제 cascade(소스 단위 그래프 삭제) 단위 테스트 (오프라인 — Neo4j driver fake 주입).

실제 Cypher 의미론(Actor가 정말 살아남는지 등)은 live Neo4j가 필요해 integration 영역이다.
여기서는 그 Cypher를 결정하는 **파라미터와 단계 구성**을 고정한다 — 소스 대문자 정규화,
alias 접두사(`SLACK:`), 다섯 단계(도메인 노드 → 고아 File → Actor → ActorDecision → 고아
__stub__)가 모두 실행되는지, 그리고 살아남은 Actor의 alias 제거가 표시 이름 재계산
(recompute_display_name)을 같은 세션으로 트리거하는지.
"""

import asyncio
import unittest
from unittest.mock import call, patch

from graph.maintenance import delete_project_source_graph


class _FakeCounters:
    def __init__(self, nodes_deleted):
        self.nodes_deleted = nodes_deleted


class _FakeSummary:
    def __init__(self, nodes_deleted):
        self.counters = _FakeCounters(nodes_deleted)


class _FakeResult:
    def __init__(self, nodes_deleted, records=None):
        self._nodes_deleted = nodes_deleted
        self._records = records if records is not None else []

    async def consume(self):
        return _FakeSummary(self._nodes_deleted)

    async def data(self):
        return self._records


class _FakeTx:
    """execute_write가 콜백에 넘기는 트랜잭션 스텁. run()을 세션에 위임해 session.calls에
    그대로 기록되게 하면서도, 이 tx 객체 자체는 "같은 트랜잭션 안"임을 구분하는 표식이 된다
    — recompute_display_name이 이 tx와 동일한 객체로 호출됐는지로 원자성을 검증할 수 있다.
    """

    def __init__(self, session):
        self._session = session

    async def run(self, query, **params):
        return await self._session.run(query, **params)


class _FakeSession:
    """실행된 (query, params)를 순서대로 기록하고, 정해진 삭제 수를 차례로 돌려준다.

    survivor_uuids는 "살아남은 Actor의 alias 제거" 쿼리가 반환하는 영향받은 Actor uuid를
    흉내낸다 — 실제 구현은 그 쿼리 결과에서만 .data()를 읽으므로, 다른 단계의 _FakeResult에
    같은 레코드를 넣어둬도(호출되지 않으니) 무해하다.
    """

    def __init__(self, deleted_counts, survivor_uuids=()):
        self.calls = []
        self._deleted_counts = list(deleted_counts)
        self._survivor_records = [{"uuid": u} for u in survivor_uuids]
        self.execute_write_calls = []  # 실행된 tx 콜백마다 넘겨준 _FakeTx 인스턴스를 기록

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        count = self._deleted_counts.pop(0) if self._deleted_counts else 0
        return _FakeResult(count, records=self._survivor_records)

    async def execute_write(self, tx_func):
        tx = _FakeTx(self)
        self.execute_write_calls.append(tx)
        return await tx_func(tx)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


def _run(project_id, source, deleted_counts=(0, 0, 0, 0, 0, 0, 0), survivor_uuids=()):
    session = _FakeSession(deleted_counts, survivor_uuids=survivor_uuids)
    # recompute_display_name은 실제 Neo4j 트랜잭션(MATCH/OPTIONAL MATCH 여러 단계)을 필요로 해
    # 이 fake session으로는 동작할 수 없다 — 여기서는 "호출되는지·어떤 인자로"만 확인하는
    # 계층이므로 항상 patch한다.
    with patch("graph.maintenance.get_driver", return_value=_FakeDriver(session)), \
         patch("graph.maintenance.recompute_display_name") as mock_recompute:
        result = asyncio.run(delete_project_source_graph(project_id, source))
    return result, session, mock_recompute


class DeleteProjectSourceGraph(unittest.TestCase):
    def test_scopes_every_step_to_project_and_source(self):
        _, session, _mock_recompute = _run("p1", "SLACK")

        # 모든 쿼리가 project_id로 스코프된다 — 다른 프로젝트를 건드리면 안 된다
        for _query, params in session.calls:
            self.assertEqual(params["project_id"], "p1")

        # 도메인 노드 삭제만 source 속성을 쓰고, Actor 계열은 alias 접두사를 쓴다
        node_params = session.calls[0][1]
        self.assertEqual(node_params["source"], "SLACK")
        alias_params = [p for _q, p in session.calls if "alias_prefix" in p]
        self.assertTrue(alias_params)
        for params in alias_params:
            self.assertEqual(params["alias_prefix"], "SLACK:")

    def test_lowercase_source_is_normalized(self):
        # backend가 provider를 소문자(`slack`)로 넘겨도 저장된 노드(SLACK)와 맞아야 한다
        _, session, _mock_recompute = _run("p1", "slack")

        self.assertEqual(session.calls[0][1]["source"], "SLACK")
        self.assertEqual(
            [p["alias_prefix"] for _q, p in session.calls if "alias_prefix" in p],
            ["SLACK:", "SLACK:", "SLACK:", "SLACK:"],
        )

    def test_runs_all_five_cleanup_stages(self):
        _, session, _mock_recompute = _run("p1", "GITHUB")

        queries = [query for query, _params in session.calls]
        joined = "\n".join(queries)
        # 도메인 노드 / 고아 File / ActorAlias 인덱스 / Actor / ActorDecision / 고아 __stub__
        self.assertIn("{project_id: $project_id, source: $source}", queries[0])
        self.assertIn(":File", joined)
        self.assertIn("NOT (f)<-[:MODIFIED]-()", joined)
        self.assertIn(":ActorAlias", joined)
        self.assertIn(":Actor {project_id: $project_id}", joined)
        self.assertIn(":ActorDecision", joined)
        self.assertIn(":Issue {project_id: $project_id, source: '__stub__'}", joined)

    def test_orphan_stub_cleanup_only_targets_edgeless_stubs(self):
        # 마지막 단계는 __stub__ 센티널 중 엣지가 하나도 안 남은 것만 지운다 — 다른 소스가
        # 여전히 참조 중인 stub(엣지 있음)은 이 조건에 걸리지 않아 살아남는다.
        _, session, _mock_recompute = _run("p1", "GITHUB")

        stub_query = next(
            query for query, _params in session.calls
            if "source: '__stub__'" in query
        )
        self.assertIn("NOT (s)--()", stub_query)
        self.assertIn("DETACH DELETE s", stub_query)

    def test_returns_counts_per_stage(self):
        # 삭제 수를 읽는 단계는 다섯(노드·File·Actor·Decision·stub) — 사이 두 쿼리는 수를 세지 않는다
        result, _session, _mock_recompute = _run(
            "p1", "JIRA", deleted_counts=(12, 3, 0, 2, 0, 1, 4)
        )

        self.assertEqual(
            result, {"nodes": 12, "files": 3, "actors": 2, "decisions": 1, "stubs": 4}
        )

    def test_blank_input_is_noop(self):
        # backend가 빈 값을 넘겨도 프로젝트 전체를 쓸어가는 쿼리가 나가면 안 된다
        for project_id, source in (("", "SLACK"), ("p1", ""), ("", "")):
            session = _FakeSession([])
            with patch("graph.maintenance.get_driver", return_value=_FakeDriver(session)):
                result = asyncio.run(delete_project_source_graph(project_id, source))
            self.assertEqual(
                result, {"nodes": 0, "files": 0, "actors": 0, "decisions": 0, "stubs": 0}
            )
            self.assertEqual(session.calls, [])

    def test_survivor_alias_removal_recomputes_display_name(self):
        # 살아남은 Actor(다른 소스 alias 보유)의 alias 제거 쿼리가 영향받은 uuid를 돌려주면,
        # 그 uuid마다 recompute_display_name이 같은 트랜잭션(tx)으로 호출돼야 한다 — 그러지
        # 않으면 지워진 소스가 표시 이름의 출처였을 때 그 개인정보가 Actor.name에 그대로 남는다.
        _, session, mock_recompute = _run(
            "p1", "GITHUB", survivor_uuids=("actor-1", "actor-2")
        )
        tx = session.execute_write_calls[0]

        self.assertEqual(
            mock_recompute.call_args_list,
            [call(tx, "actor-1"), call(tx, "actor-2")],
        )

    def test_alias_decrement_and_recompute_share_one_transaction(self):
        # 차감 쿼리와 재계산 사이에서 프로세스가 죽으면, 재실행 시 차감 쿼리의 WHERE가 더 이상
        # 매치되지 않아 재계산이 영구 누락된다 — 그래서 둘은 execute_write 하나로 원자적이어야
        # 한다(graph.privacy.apply_report와 같은 패턴). tx 인자가 동일 객체인지로 검증한다.
        _, session, mock_recompute = _run(
            "p1", "GITHUB", survivor_uuids=("actor-1", "actor-2")
        )

        self.assertEqual(len(session.execute_write_calls), 1)
        tx = session.execute_write_calls[0]
        for recompute_call in mock_recompute.call_args_list:
            self.assertIs(recompute_call.args[0], tx)

        alias_decrement_queries = [
            q for q, p in session.calls
            if "SET a.aliases = [alias IN a.aliases" in q and "alias_prefix" in p
        ]
        self.assertEqual(len(alias_decrement_queries), 1)

    def test_no_survivors_skips_recompute(self):
        # 이 소스의 alias를 가진 살아남은 Actor가 없으면 재계산도 없다
        _, _session, mock_recompute = _run("p1", "GITHUB", survivor_uuids=())

        mock_recompute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
