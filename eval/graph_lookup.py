"""골든셋 evidence id ↔ Neo4j 노드 해석 공용 모듈.

validate_golden.py(존재 검증)와 grader.py(LLM judge에 줄 evidence 원문 조회)가 공유한다.
id 표기는 골든셋/'/query' 응답 규칙을 따른다:
  issue -> jira_key / pull_request -> #번호 / commit -> hash 앞 7자 / message -> conversation_id(루트 ts)

접속 정보는 NEO4J_URI / NEO4J_USER / NEO4J_PASSWORD 환경변수로 덮어쓸 수 있다.
"""

import os
import re
import sys

from neo4j import GraphDatabase

ID_RE = re.compile(r"^(issue|pull_request|commit|message):(.+)$")

# id 타입별 (형식 검사 정규식, 설명)
FORMAT_RULES = {
    "issue": (re.compile(r"^HT-\d+$"), "jira_key (예: HT-3)"),
    "pull_request": (re.compile(r"^#\d+$"), "#번호 (예: #19)"),
    "commit": (re.compile(r"^[0-9a-f]{7}$"), "hash 앞 7자 소문자"),
    "message": (re.compile(r"^\d+\.\d+$"), "conversation_id = 스레드 루트 ts"),
}


def open_driver():
    uri = os.environ.get("NEO4J_URI", "bolt://localhost:7687")
    user = os.environ.get("NEO4J_USER", "neo4j")
    password = os.environ.get("NEO4J_PASSWORD", "password1234")
    return GraphDatabase.driver(uri, auth=(user, password))


def detect_project_id(session) -> str:
    """그래프에 프로젝트가 하나뿐이면 그 project_id를 반환, 아니면 안내 후 종료."""
    rows = session.run(
        "MATCH (n) WHERE n.project_id IS NOT NULL "
        "RETURN n.project_id AS pid, count(*) AS cnt ORDER BY cnt DESC"
    ).data()
    if not rows:
        sys.exit("그래프에 project_id를 가진 노드가 없습니다 — 스냅샷이 로드됐는지 확인하세요.")
    if len(rows) > 1:
        listing = "\n".join(f"  {r['pid']}  ({r['cnt']} nodes)" for r in rows)
        sys.exit(f"프로젝트가 여러 개입니다. --project-id로 지정하세요:\n{listing}")
    return rows[0]["pid"]


def resolve(session, pid: str, id_type: str, value: str) -> tuple[int, str]:
    """id가 그래프 노드로 해석되는지 확인. (매치 수, 부가 설명) 반환."""
    if id_type == "issue":
        q = "MATCH (n:Issue {project_id:$pid, jira_key:$v}) RETURN count(n) AS c"
        return session.run(q, pid=pid, v=value).single()["c"], ""
    if id_type == "pull_request":
        q = ("MATCH (n:PullRequest {project_id:$pid}) "
             "WHERE toString(n.pr_number) = $v RETURN count(n) AS c")
        return session.run(q, pid=pid, v=value.lstrip("#")).single()["c"], ""
    if id_type == "commit":
        q = ("MATCH (n:ChangeSet {project_id:$pid}) "
             "WHERE n.hash STARTS WITH $v RETURN count(n) AS c")
        c = session.run(q, pid=pid, v=value).single()["c"]
        return c, ("prefix가 커밋 여러 개와 매치" if c > 1 else "")
    if id_type == "message":
        q = ("MATCH (n:Communication {project_id:$pid}) "
             "WHERE n.conversation_id = $v RETURN count(n) AS c")
        c = session.run(q, pid=pid, v=value).single()["c"]
        if c == 0:
            # 리플라이 ts 등 conversation_id에 없는 ts — Slack url의 p<ts숫자>로 재시도
            q2 = ("MATCH (n:Communication {project_id:$pid}) "
                  "WHERE n.url CONTAINS $digits RETURN count(n) AS c")
            c2 = session.run(q2, pid=pid, digits="p" + value.replace(".", "")).single()["c"]
            if c2 > 0:
                return c2, "conversation_id 아님 — url의 개별 메시지 ts로만 매치 (루트 ts인지 확인 필요)"
        return c, ""
    raise ValueError(id_type)


def fetch_evidence_body(session, pid: str, id_type: str, value: str) -> dict | None:
    """LLM judge에 제공할 evidence 원문(그래프 노드 실제 내용)을 조회한다.

    환각률 판정에는 답변의 quote가 아니라 원문 전체가 필요하다 (measurement-plan.md).
    없으면 None — 존재하지 않는 노드를 인용한 경우이며 채점기가 별도 표시한다.
    """
    if id_type == "issue":
        row = session.run(
            "MATCH (n:Issue {project_id:$pid, jira_key:$v}) "
            "RETURN n.jira_key AS key, n.title AS title, n.body AS body, n.status AS status "
            "LIMIT 1",
            pid=pid, v=value,
        ).single()
        return dict(row) if row else None
    if id_type == "pull_request":
        row = session.run(
            "MATCH (n:PullRequest {project_id:$pid}) WHERE toString(n.pr_number) = $v "
            "RETURN n.pr_number AS pr_number, n.title AS title, n.body AS body LIMIT 1",
            pid=pid, v=value.lstrip("#"),
        ).single()
        return dict(row) if row else None
    if id_type == "commit":
        row = session.run(
            "MATCH (n:ChangeSet {project_id:$pid}) WHERE n.hash STARTS WITH $v "
            "OPTIONAL MATCH (n)-[m:MODIFIED]->(f:File) "
            "RETURN n.hash AS hash, n.message AS message, "
            "collect({path: f.path, diff_summary: m.diffSummary})[..30] AS files LIMIT 1",
            pid=pid, v=value,
        ).single()
        return dict(row) if row else None
    if id_type == "message":
        def thread(cid: str) -> list[dict]:
            return session.run(
                "MATCH (n:Communication {project_id:$pid, conversation_id:$cid}) "
                "OPTIONAL MATCH (a:Actor)-[:WROTE]->(n) "
                "RETURN n.body AS body, toString(n.occurredAt) AS occurred_at, "
                "n.channel AS channel, a.name AS author "
                "ORDER BY n.occurredAt",
                pid=pid, cid=cid,
            ).data()

        rows = thread(value)
        if rows:
            return {"conversation_id": value, "messages": rows}
        # 시스템이 루트 ts 대신 리플라이 ts로 인용하는 알려진 결함 — resolve()와 같은
        # url 폴백으로 노드를 찾아 스레드 전체를 반환한다. 여기서 None을 주면 judge가
        # 실재하는 메시지 근거 문장을 환각으로 오판해 측정이 시스템에 불리하게 왜곡된다.
        row = session.run(
            "MATCH (n:Communication {project_id:$pid}) WHERE n.url CONTAINS $digits "
            "RETURN n.conversation_id AS cid LIMIT 1",
            pid=pid, digits="p" + value.replace(".", ""),
        ).single()
        if row and row["cid"]:
            rows = thread(row["cid"])
            if rows:
                return {
                    "conversation_id": row["cid"],
                    "resolved_from_reply_ts": value,
                    "messages": rows,
                }
        return None
    raise ValueError(id_type)
