import json
import logging

from graph.embedder import embed_text
from tools import queries

logger = logging.getLogger(__name__)


async def execute(tool_name: str, args: dict) -> str:
    """tool_name과 args를 받아 해당 Cypher 쿼리를 실행하고 JSON 문자열로 반환."""
    try:
        result = await _dispatch(tool_name, args)
    except Exception as e:
        logger.exception("도구 실행 실패: %s args=%s", tool_name, args)
        result = {"error": f"도구 실행 중 오류가 발생했습니다: {e}"}

    return json.dumps(result, ensure_ascii=False, default=str)


async def _dispatch(tool_name: str, args: dict) -> object:
    match tool_name:
        case "get_issue_context":
            return await queries.get_issue_context(
                jira_key=args["jira_key"],
            )

        case "get_changeset_context":
            return await queries.get_changeset_context(
                hash=args["hash"],
            )

        case "find_expert":
            return await queries.find_expert(
                path_prefix=args["path_prefix"],
            )

        case "get_timeline":
            return await queries.get_timeline(
                jira_key=args["jira_key"],
            )

        case "search_by_keyword":
            # LLM이 keyword 문자열을 전달 → executor에서 임베딩 생성
            embedding = await embed_text(args["keyword"])
            return await queries.search_by_keyword(
                embedding=embedding,
                top_k=args.get("top_k", 5),
                threshold=args.get("threshold", 0.30),
            )

        case "get_actor_activity":
            return await queries.get_actor_activity(
                identifier=args["identifier"],
                from_time=args.get("from_time"),
                limit=args.get("limit", 20),
            )

        case "get_file_history":
            return await queries.get_file_history(
                path=args["path"],
                limit=args.get("limit", 20),
            )

        case "check_missing_context":
            return await queries.check_missing_context(
                from_time=args.get("from_time"),
                to_time=args.get("to_time"),
                limit=args.get("limit", 50),
            )

        case "inspect_actor":
            return await queries.inspect_actor(
                identifier=args["identifier"],
            )

        case "get_conflict_context":
            return await queries.get_conflict_context(
                hash=args["hash"],
            )

        case "get_recent_activity":
            return await queries.get_recent_activity(
                from_time=args["from_time"],
                to_time=args.get("to_time"),
                limit=args.get("limit", 30),
            )

        case "get_pr_context":
            return await queries.get_pr_context(
                pr_number=args["pr_number"],
            )

        case "get_thread_context":
            return await queries.get_thread_context(
                conversation_id=args["conversation_id"],
            )

        case _:
            raise ValueError(f"알 수 없는 도구: {tool_name}")
