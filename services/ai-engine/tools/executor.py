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


async def execute(tool_name: str, args: dict, project_id: str, question: str = "") -> str:
    """tool_name과 args를 받아 해당 Cypher 쿼리를 실행하고 JSON 문자열로 반환.

    project_id는 backend가 인증된 사용자의 프로젝트로 주입한 값 — 모든 쿼리가 이 값으로
    스코프되어 다른 프로젝트 그래프를 조회하지 못한다. LLM은 project_id를 보지도 못하고
    바꿀 수도 없다 (도구 인자에 없음).

    question은 사용자 원 질문 — get_file_history가 이력을 질문 관련도로 재랭킹할 때 쓴다
    (executor에서 임베딩; search_by_keyword의 키워드 임베딩과 같은 경로).
    """
    try:
        result = await _dispatch(tool_name, args, project_id, question)
    except KeyError as e:
        # 필수 인자 누락 — LLM이 다음 호출에서 교정할 수 있도록 명확히 알림
        result = {"error": f"필수 인자 누락: {e.args[0] if e.args else 'unknown'}"}
    except Exception:
        # 내부 정보(스택트레이스/연결 문자열 등) 노출 차단. 로그에만 남김.
        logger.exception("도구 실행 실패: %s args=%s", tool_name, _mask_args(args))
        result = {"error": f"{tool_name} 실행 중 내부 오류가 발생했습니다."}

    payload = json.dumps(result, ensure_ascii=False, default=_json_default)
    if len(payload) > _MAX_RESULT_CHARS:
        payload = _truncate_payload(result, payload)
    return payload


def _truncate_payload(result, payload: str) -> str:
    """상한 초과 결과를 자른다.

    list면 행 단위로 줄여 JSON을 깨뜨리지 않고, 몇 건이 생략됐는지 마커 원소로 알린다 —
    문자열 중간을 자르면 JSON이 파손되고 뒤쪽 행이 증발한 사실이 숨겨져, LLM이 "이게
    전부"라고 믿게 된다(case-27: 파일 이력의 오래된 커밋 누락). 정렬 방향과 무관하게
    "뒷부분 생략"만 사실로 알리고, 재호출 방법은 범위 축소로 안내한다(limit 축소 안내는
    최신순 결과에서 잘린 옛 행을 영영 못 보게 하는 역효과가 있었다).
    """
    if isinstance(result, list) and len(result) > 1:
        total = len(result)
        kept = list(result)
        while len(kept) > 1:
            kept.pop()
            marker = {
                "_truncated": (
                    f"전체 {total}건 중 앞 {len(kept)}건만 표시 — 뒷부분 {total - len(kept)}건 생략. "
                    "나머지가 필요하면 from_time/to_time 등으로 범위를 좁혀 다시 호출하세요."
                )
            }
            candidate = json.dumps(kept + [marker], ensure_ascii=False, default=_json_default)
            if len(candidate) <= _MAX_RESULT_CHARS:
                return candidate
        # 한 행만으로도 상한 초과 — 어쩔 수 없이 문자열 컷 (아래 dict 경로와 동일)
    # get_file_history 등 detail/context 계층 dict — 문자열 컷(JSON 파손)으로 떨어뜨리지 않고
    # 개요(context)부터, 그래도 넘치면 인용 대상(detail)까지 행 단위로 줄인다. 문자열 중간
    # 컷은 JSON을 깨고 오래된 행 증발을 숨긴다(case-27) — 이 dict는 항상 유효 JSON을 보장.
    if isinstance(result, dict) and (
        isinstance(result.get("context"), list) or isinstance(result.get("detail"), list)
    ):
        trimmed = _trim_tiered_dict(result)
        if trimmed is not None:
            return trimmed
    # get_timeline의 events dict — 계층 dict가 아니라 위 트리머가 못 잡는다.
    if isinstance(result, dict) and isinstance(result.get("events"), list):
        trimmed = _trim_timeline_dict(result)
        if trimmed is not None:
            return trimmed
    return payload[:_MAX_RESULT_CHARS] + " ...[결과 뒷부분이 잘렸습니다 — JSON이 불완전할 수 있습니다. 더 좁은 범위로 다시 호출하세요.]"


def _trim_timeline_dict(result: dict) -> str | None:
    """시간축 events dict를 상한 이하로 줄인다 — 항상 유효 JSON 반환.

    오름차순 정렬이므로 **뒤에서** 자른다(= 최근 쪽을 버리고 시작을 남긴다).
    시간축 질문은 시작 시점이 답의 일부라 앞을 버리면 '언제 시작됐나'가 사라진다
    (query-quality-issues 문제 2). 자른 뒤에는 window.covered_to를 실제 마지막
    이벤트로 되돌려, 모델이 원래 범위를 그대로 믿지 않게 한다.
    """
    def dumps(obj) -> str:
        return json.dumps(obj, ensure_ascii=False, default=_json_default)

    events = result["events"]
    if not events:
        return None

    work = dict(result)
    kept = list(events)
    while kept:
        work["events"] = kept
        if len(kept) < len(events):
            work["window"] = {
                **(result.get("window") or {}),
                "covered_to": kept[-1].get("occurredAt"),
            }
            work["truncated"] = (
                f"전체 {result.get('total_events', len(events))}건 중 오래된 순 {len(kept)}건만 "
                f"표시 — 상한 초과로 뒤 구간 생략. covered_to가 실제 마지막 사건이 아닙니다. "
                f"뒤가 필요하면 from_time을 올려 다시 호출하세요."
            )
        candidate = dumps(work)
        if len(candidate) <= _MAX_RESULT_CHARS:
            return candidate
        kept.pop()
    return None


def _trim_tiered_dict(result: dict) -> str | None:
    """detail/context 계층 dict를 상한 이하로 줄인다 — 항상 유효 JSON 반환.

    개요(context)를 먼저 비우고, 그래도 초과하면 인용 대상(detail)을 뒤에서 줄이되
    최소 1건은 남긴다(조용한 전멸 방지). 1건 detail로도 초과하면 None(호출부가 문자열 컷).
    줄인 사실은 *_truncated 필드로 고지한다.
    """
    def dumps(obj) -> str:
        return json.dumps(obj, ensure_ascii=False, default=_json_default)

    work = dict(result)

    ctx = work.get("context")
    if isinstance(ctx, list) and ctx:
        full = len(ctx)
        kept = list(ctx)
        while True:
            work["context"] = kept
            if len(kept) < full:
                work["context_truncated"] = (
                    f"context 개요 {full}건 중 앞 {len(kept)}건만 표시 — 상한 초과로 "
                    f"{full - len(kept)}건 축약. 필요하면 get_changeset_context로 개별 조회."
                )
            candidate = dumps(work)
            if len(candidate) <= _MAX_RESULT_CHARS:
                return candidate
            if not kept:
                break
            kept.pop()

    det = work.get("detail")
    if isinstance(det, list) and len(det) > 1:
        full = len(det)
        kept = list(det)
        while len(kept) > 1:
            kept.pop()
            work["detail"] = kept
            work["detail_truncated"] = (
                f"detail {full}건 중 앞 {len(kept)}건만 표시 — 상한 초과로 {full - len(kept)}건 축약. "
                f"나머지는 context 개요 또는 get_changeset_context로 확인."
            )
            candidate = dumps(work)
            if len(candidate) <= _MAX_RESULT_CHARS:
                return candidate

    return None


async def _question_embedding(question: str) -> list[float] | None:
    """질문 관련도 재랭킹용 임베딩 — 질문 없거나 임베딩 실패 시 None(호출부 최신순 폴백).

    search_by_keyword의 키워드 임베딩과 같은 경로로 INTERACTIVE 우선순위(질의 latency 보호).
    """
    if not question or not question.strip():
        return None
    return await embed_text(question, priority=Priority.INTERACTIVE) or None


async def _dispatch(tool_name: str, args: dict, project_id: str, question: str = "") -> object:
    match tool_name:
        case "get_issue_context":
            return await queries.get_issue_context(
                project_id=project_id,
                issue_key=args["issue_key"],
                source=args.get("source"),
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
            # 스코프 인자는 전부 선택 — 넷 다 없으면 프로젝트 전체 기간이다.
            return await queries.get_timeline(
                project_id=project_id,
                issue_key=args.get("issue_key"),
                path=args.get("path"),
                actor=args.get("actor"),
                from_time=args.get("from_time"),
                to_time=args.get("to_time"),
                source=args.get("source"),
            )

        case "rank_issues":
            return await queries.rank_issues(
                project_id=project_id,
                by=args.get("by", "discussion"),
                top_k=args.get("top_k", 5),
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
            # limit은 의도적으로 전달하지 않는다 — 조회 창은 서버 정책(ACTOR_ACTIVITY_MAX)이
            # 결정하고, LLM이 지어낸 limit 인자가 창을 옛 컷(20)으로 되돌리지 못하게 한다
            return await queries.get_actor_activity(
                project_id=project_id,
                identifier=args["identifier"],
                from_time=args.get("from_time"),
                question_embedding=await _question_embedding(question),
            )

        case "get_file_history":
            return await queries.get_file_history(
                project_id=project_id,
                path=args["path"],
                question_embedding=await _question_embedding(question),
                limit=args.get("limit"),
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

        case "run_graph_query":
            # purpose는 실행에 쓰지 않는다 — 어떤 의도의 쿼리였는지 로그로 남겨,
            # 나중에 라우팅 오염(전용 도구가 있는 질문에 Cypher를 쓴 경우)을 추적한다.
            logger.info("run_graph_query 의도: %s", args.get("purpose", ""))
            return await queries.run_graph_query(
                project_id=project_id,
                cypher=args["cypher"],
            )

        case "describe_graph":
            return await queries.describe_graph(
                project_id=project_id,
                label=args["label"],
            )

        case _:
            raise ValueError(f"알 수 없는 도구: {tool_name}")
