"""
Neo4j 그래프 빌더 — facade.

원래 단일 모듈이던 빌더를 책임별 모듈로 분리(Phase 1 리팩토링)하고, 하위 호환을 위해
공개 심볼을 여기서 그대로 re-export 한다. 기존 `from graph.builder import X` 와
`from graph import builder; builder.X` 코드가 모두 그대로 동작한다.

분리된 모듈:
- graph.driver              : 드라이버 수명주기 (get_driver, close_driver)
- graph.schema              : 인덱스/제약 부트스트랩
- graph.writes              : NormalizedEvent 단위 upsert/link (수집 쓰기 경로)
- graph.maintenance         : 백필/마이그레이션/정리/프로젝트 삭제
- graph.reference_store     : REFERENCE 엣지용 Neo4j ReferenceStore 어댑터
- graph.issue_link_store    : 시맨틱 이슈 링크용 Neo4j IssueLinkStore 어댑터
- graph.actor_store         : Actor 동일인 판단용 Neo4j ActorStore 어댑터
- graph.communication_store : Slack 필터용 Communication 조회/정리
"""

from graph.actor_store import make_neo4j_actor_store
from graph.communication_store import (
    delete_communication,
    fetch_unfiltered_communications,
    mark_communication_llm_filtered,
)
from graph.driver import close_driver, get_driver
from graph.issue_link_store import make_neo4j_issue_link_store
from graph.maintenance import (
    backfill_actor_aliases,
    backfill_discussed_in_source,
    backfill_pr_issue_keys,
    backfill_triggered_by_source,
    clear_reference,
    clear_semantic_discussed_in,
    clear_semantic_triggered_by,
    delete_project_graph,
    delete_project_source_graph,
    propagate_thread_discussed_in,
    verify_actor_name_consistency,
)
from graph.reference_store import make_neo4j_reference_store
from graph.schema import drop_node_search_index, ensure_constraints, ensure_vector_indexes
from graph.writes import (
    link_changeset_to_issue,
    link_changeset_to_pr_issues,
    link_issue_to_assignee,
    link_issue_to_communication,
    link_issue_to_parent,
    link_pr_changesets_to_issues,
    link_pr_to_changeset,
    unlink_issue_assignees,
    upsert_changeset,
    upsert_communication,
    upsert_file_with_modified_edge,
    upsert_files_with_modified_edges,
    upsert_issue,
    upsert_pull_request,
)

__all__ = [
    # driver / schema
    "get_driver",
    "close_driver",
    "ensure_vector_indexes",
    "drop_node_search_index",
    "ensure_constraints",
    # writes (upsert)
    "upsert_changeset",
    "upsert_file_with_modified_edge",
    "upsert_files_with_modified_edges",
    "upsert_pull_request",
    "upsert_issue",
    "upsert_communication",
    # writes (link)
    "link_changeset_to_issue",
    "link_changeset_to_pr_issues",
    "link_pr_to_changeset",
    "link_pr_changesets_to_issues",
    "link_issue_to_communication",
    "link_issue_to_parent",
    "link_issue_to_assignee",
    "unlink_issue_assignees",
    # maintenance / migrations
    "propagate_thread_discussed_in",
    "backfill_triggered_by_source",
    "backfill_discussed_in_source",
    "backfill_pr_issue_keys",
    "backfill_actor_aliases",
    "clear_semantic_triggered_by",
    "clear_semantic_discussed_in",
    "clear_reference",
    "delete_project_graph",
    "delete_project_source_graph",
    "verify_actor_name_consistency",
    # store factories
    "make_neo4j_reference_store",
    "make_neo4j_issue_link_store",
    "make_neo4j_actor_store",
    # communication filter helpers
    "fetch_unfiltered_communications",
    "mark_communication_llm_filtered",
    "delete_communication",
]
