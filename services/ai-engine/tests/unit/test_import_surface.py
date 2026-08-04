"""Import / public-surface smoke test — the safety net for the planned refactor.

This runs fully offline (no Neo4j, no real OpenAI key — see ../../conftest.py).
Its job is to fail loudly when a module split breaks an import or drops a name
that other modules depend on:

* ``test_module_imports`` — every first-party module must still import. Catches
  syntax errors, broken/circular imports, and missing re-exports introduced
  while splitting large modules.
* ``test_*_public_surface`` — when ``graph/builder.py`` (Phase 1) and
  ``tools/queries.py`` (Phase 2) are broken into packages, the thin facade must
  keep re-exporting these names. The lists below are the *actual* contract:
  every name imported from these modules elsewhere in the codebase.
"""

import importlib

import pytest

# Every first-party module. New modules created by a refactor should be added
# here so they stay covered.
MODULES = [
    "main",
    "openai_client",
    "query_models",
    "rate_limiter",
    "agent.orchestrator",
    "graph.actor_admin",
    "graph.actor_llm",
    "graph.actor_resolver",
    "graph.actor_store",
    "graph.builder",
    "graph.communication_store",
    "graph.consumer",
    "graph.driver",
    "graph.embedder",
    "graph.event_handler",
    "graph.issue_link_store",
    "graph.issue_linker",
    "graph.issue_verifier",
    "graph.llm_judge",
    "graph.maintenance",
    "graph.overview",
    "graph.path_filter",
    "graph.postprocess",
    "graph.privacy",
    "graph.project_context",
    "graph.reference_builder",
    "graph.reference_store",
    "graph.reference_verifier",
    "graph.schema",
    "graph.slack_batch_filter",
    "graph.slack_filter",
    "graph.slack_llm_filter",
    "graph.summarizer",
    "graph.writes",
    "tools.definitions",
    "tools.executor",
    "tools.queries",
    "tools.queries._common",
    "tools.queries.actor",
    "tools.queries.changeset",
    "tools.queries.discovery",
    "tools.queries.explore",
    "tools.queries.files",
    "tools.queries.issue",
    "routers.admin",
    "routers.graph",
    "routers.privacy",
    "routers.query",
]


@pytest.mark.parametrize("module_name", MODULES)
def test_module_imports(module_name):
    importlib.import_module(module_name)


# Names imported from graph.builder elsewhere (main.py, tools/queries.py,
# graph/overview.py, graph/postprocess.py, graph/event_handler.py,
# graph/slack_batch_filter.py). Must survive the Phase 1 split.
BUILDER_PUBLIC = [
    "get_driver",
    "close_driver",
    "ensure_vector_indexes",
    "drop_node_search_index",
    "ensure_constraints",
    "delete_project_graph",
    "delete_project_source_graph",
    "backfill_actor_aliases",
    "backfill_pr_jira_keys",
    "backfill_triggered_by_source",
    "backfill_discussed_in_source",
    "clear_reference",
    "clear_semantic_triggered_by",
    "clear_semantic_discussed_in",
    "propagate_thread_discussed_in",
    "verify_actor_name_consistency",
    "make_neo4j_reference_store",
    "make_neo4j_issue_link_store",
    "make_neo4j_actor_store",
    "fetch_unfiltered_communications",
    "mark_communication_llm_filtered",
    "delete_communication",
]

# Public query functions (one per LLM tool — see docs/tools.md). Must survive
# the Phase 2 split of tools/queries.py.
QUERIES_PUBLIC = [
    "get_issue_context",
    "get_changeset_context",
    "find_expert",
    "get_timeline",
    "rank_issues",
    "search_by_keyword",
    "get_actor_activity",
    "get_file_history",
    "check_missing_context",
    "inspect_actor",
    "get_conflict_context",
    "get_recent_activity",
    "get_pr_context",
    "get_thread_context",
    "run_graph_query",
    "describe_graph",
    "SCHEMA_CARD",
]

ORCHESTRATOR_PUBLIC = ["run", "summarize_history"]


@pytest.mark.parametrize("name", BUILDER_PUBLIC)
def test_builder_public_surface(name):
    mod = importlib.import_module("graph.builder")
    assert hasattr(mod, name), f"graph.builder must export {name!r}"


@pytest.mark.parametrize("name", QUERIES_PUBLIC)
def test_queries_public_surface(name):
    mod = importlib.import_module("tools.queries")
    assert hasattr(mod, name), f"tools.queries must export {name!r}"


@pytest.mark.parametrize("name", ORCHESTRATOR_PUBLIC)
def test_orchestrator_public_surface(name):
    mod = importlib.import_module("agent.orchestrator")
    assert hasattr(mod, name), f"agent.orchestrator must export {name!r}"
