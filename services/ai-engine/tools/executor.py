import json
import logging
import re
from datetime import date, datetime

from graph.embedder import embed_text
from openai_client import Priority
from tools import queries

logger = logging.getLogger(__name__)

_MAX_RESULT_CHARS = 8000  # tool 결과를 LLM 컨텍스트에 누적할 때 상한 (약 4k 토큰)
_EMAIL_PATTERN = re.compile(r"([^\s@]{1,3})[^\s@]*@([^\s]+)")


def _json_default(obj):
    """ISO-8601 형식으로 datetime/date 직렬화. 그 외는 str()."""
    if isinstance(obj, (datetime, date)):
        return obj.isoformat()
    return str(obj)


def _mask_value(value):
    """문자열 값 안의 이메일을 마스킹한다. 그 외 타입은 그대로 반환."""
    if not isinstance(value, str):
        return value
    return _EMAIL_PATTERN.sub(lambda m: f"{m.group(1)}***@{m.group(2)}", value)


def _mask_args(args: dict) -> dict:
    return {k: _mask_value(v) for k, v in args.items()}


async def execute(tool_name: str, args: dict, project_id: str) -> str:
    """tool_name과 args를 받아 해당 Cypher 쿼리를 실행하고 JSON 문자열로 반환.

    project_id는 backend가 인증된 사용자의 프로젝트로 주입한 값 — 모든 쿼리가 이 값으로
    스코프되어 다른 프로젝트 그래프를 조회하지 못한다. LLM은 project_id를 보지도 못하고
    바꿀 수도 없다 (도구 인자에 없음).
    """
    try:
        result = await _dispatch(tool_name, args, project_id)
    except KeyError as e:
        # 필수 인자 누락 — LLM이 다음 호출에서 교정할 수 있도록 명확히 알림
        result = {"error": f"필수 인자 누락: {e.args[0] if e.args else 'unknown'}"}
    except Exception:
        # 내부 정보(스택트레이스/연결 문자열 등) 노출 차단. 로그에만 남김.
        logger.exception("도구 실행 실패: %s args=%s", tool_name, _mask_args(args))
        result = {"error": f"{tool_name} 실행 중 내부 오류가 발생했습니다."}

    payload = json.dumps(result, ensure_ascii=False, default=_json_default)
    if len(payload) > _MAX_RESULT_CHARS:
        payload = payload[:_MAX_RESULT_CHARS] + " ...[결과가 잘렸습니다. limit을 줄이거나 더 좁은 범위로 다시 호출하세요.]"
    return payload


async def _dispatch(tool_name: str, args: dict, project_id: str) -> object:
    match tool_name:
        case "get_issue_context":
            return await queries.get_issue_context(
                project_id=project_id,
                jira_key=args["jira_key"],
            )

        case "get_changeset_context":
            return await queries.get_changeset_context(
                project_id=project_id,
                hash=args["hash"],
            )

        case "find_expert":
            return await queries.find_expert(
                project_id=project_id,
                path_prefix=args["path_prefix"],
            )

        case "get_timeline":
            return await queries.get_timeline(
                project_id=project_id,
                jira_key=args["jira_key"],
            )

        case "search_by_keyword":
            # LLM이 keyword 문자열을 전달 → executor에서 임베딩 생성.
            # 질의 경로이므로 INTERACTIVE — 수집 임베딩보다 먼저 처리돼 질의 latency를 보호한다.
            embedding = await embed_text(args["keyword"], priority=Priority.INTERACTIVE)
            return await queries.search_by_keyword(
                project_id=project_id,
                embedding=embedding,
                top_k=args.get("top_k", 5),
                threshold=args.get("threshold", 0.30),
            )

        case "get_actor_activity":
            return await queries.get_actor_activity(
                project_id=project_id,
                identifier=args["identifier"],
                from_time=args.get("from_time"),
                limit=args.get("limit", 20),
            )

        case "get_file_history":
            return await queries.get_file_history(
                project_id=project_id,
                path=args["path"],
                limit=args.get("limit", 20),
            )

        case "check_missing_context":
            return await queries.check_missing_context(
                project_id=project_id,
                from_time=args.get("from_time"),
                to_time=args.get("to_time"),
                limit=args.get("limit", 50),
            )

        case "inspect_actor":
            return await queries.inspect_actor(
                project_id=project_id,
                identifier=args["identifier"],
            )

        case "get_conflict_context":
            return await queries.get_conflict_context(
                project_id=project_id,
                hash=args["hash"],
            )

        case "get_recent_activity":
            return await queries.get_recent_activity(
                project_id=project_id,
                from_time=args["from_time"],
                to_time=args.get("to_time"),
                limit=args.get("limit", 30),
            )

        case "get_pr_context":
            return await queries.get_pr_context(
                project_id=project_id,
                pr_number=args["pr_number"],
            )

        case "get_thread_context":
            return await queries.get_thread_context(
                project_id=project_id,
                conversation_id=args["conversation_id"],
            )

        case _:
            raise ValueError(f"알 수 없는 도구: {tool_name}")
