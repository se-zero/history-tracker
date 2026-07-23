"""
tools.queries — GraphRAG LLM 도구가 호출하는 Neo4j 읽기 쿼리.

원래 단일 모듈(queries.py)이던 것을 도메인별 모듈로 분리(Phase 2)하고, 하위 호환을 위해
공개 쿼리 함수를 여기서 re-export 한다. `from tools import queries; queries.X(...)` 가
그대로 동작한다.

- tools.queries.issue      : 이슈/에픽/타임라인
- tools.queries.changeset  : 커밋·PR·충돌·맥락 누락 커밋
- tools.queries.actor      : 사람 (전문가/활동/inspect)
- tools.queries.files      : 파일 변경 이력
- tools.queries.discovery  : 키워드/최근/스레드 탐색
- tools.queries.explore    : 범용 Cypher 조회 (검증·project_id 주입·읽기 실행)
- tools.queries._common    : 공용 드라이버/상수/헬퍼 (내부)
"""

from tools.queries.actor import find_expert, get_actor_activity, inspect_actor
from tools.queries.changeset import (
    check_missing_context,
    get_changeset_context,
    get_conflict_context,
    get_pr_context,
)
from tools.queries.discovery import (
    get_recent_activity,
    get_thread_context,
    search_by_keyword,
)
from tools.queries.explore import SCHEMA_CARD, describe_graph, run_graph_query
from tools.queries.files import get_file_history
from tools.queries.issue import get_issue_context, get_timeline, rank_issues

__all__ = [
    "run_graph_query",
    "describe_graph",
    "SCHEMA_CARD",
    "get_issue_context",
    "get_timeline",
    "rank_issues",
    "get_changeset_context",
    "check_missing_context",
    "get_conflict_context",
    "get_pr_context",
    "find_expert",
    "get_actor_activity",
    "inspect_actor",
    "get_file_history",
    "search_by_keyword",
    "get_recent_activity",
    "get_thread_context",
]
