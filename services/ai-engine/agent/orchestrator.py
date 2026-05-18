import asyncio
import json
import logging
import os

from openai import OpenAI

from tools.definitions import TOOLS
from tools.executor import execute

logger = logging.getLogger(__name__)

_client = OpenAI(api_key=os.environ["OPENAI_API_KEY"], timeout=60.0)
_MODEL = os.environ.get("QUERY_MODEL", "gpt-4o-mini")
_MAX_ITERATIONS = 10

_SYSTEM_PROMPT = """\
당신은 코드 변경 맥락 분석 AI입니다.
GitHub(커밋, PR), Jira(이슈), Slack(메시지) 데이터가 Neo4j 지식 그래프로 연결되어 있습니다.
제공된 도구를 사용해 그래프를 탐색하고 사용자의 질문에 답하세요.

[답변 규칙]
- 도구 결과에 없는 내용은 절대 추측하거나 지어내지 마세요.
- 컨텍스트가 부족하면 "확인된 정보가 없습니다"라고 명시하세요.
- 여러 출처(Jira, Slack, PR)가 서로 다른 이유를 설명하면 각 관점을 구분해 제시하세요.
- 연결 confidence가 낮은 정보(0.5 미만)는 "추정" 또는 "불확실"이라고 표시하세요.
- 한국어로 답변하세요.

[Slack/Communication 인용 규칙]
- 도구 결과의 discussions / communications / comm_contexts 필드는 conversation_id별로
  그룹핑된 구조이다: [{conversation_id, source, channel, messages:[...]}, ...].
- 서로 다른 conversation_id에 속한 메시지를 같은 대화로 합치지 마세요.
- 화자(author)와 메시지 본문(body)은 한 메시지 객체 안에서 1:1로 짝지어져 있다.
  그룹/메시지 간 author를 swap해서 인용하지 마세요.
- 메시지를 인용할 때 가능하면 conversation_id를 함께 표기해 어느 스레드인지 명확히 하세요.
- 한 스레드의 대표 메시지만 보이고 전체 흐름이 필요하면 그 conversation_id로
  get_thread_context를 호출해 전체 메시지 시퀀스를 조회하세요.

[도구 사용 가이드]
- 커밋 hash나 Jira key를 모를 때: search_by_keyword로 진입점 탐색 후 다른 도구 호출
- 코드 변경 이유: search_by_keyword → get_changeset_context
- Jira 이슈 중심 탐색: get_issue_context 또는 get_timeline
- Slack 스레드 전체 맥락(검색 결과는 스레드당 대표 1건만 노출됨): get_thread_context(conversation_id)
- 파일 담당자: find_expert
- 사람 활동 조회: get_actor_activity
- 컨텍스트 없는 커밋: check_missing_context
- 여러 출처 비교: get_conflict_context
"""

_FALLBACK_ANSWER = "답변을 생성하지 못했습니다."
_LLM_FAILURE_ANSWER = "AI 응답 생성에 실패했습니다. 잠시 후 다시 시도해주세요."


def _build_system_prompt(project_context: str = "") -> str:
    """프로젝트 컨텍스트가 있으면 시스템 프롬프트 상단에 도메인 정보를 주입한다."""
    if not project_context.strip():
        return _SYSTEM_PROMPT
    return (
        f"[프로젝트 컨텍스트]\n{project_context.strip()}\n"
        f"위 프로젝트의 도메인 용어와 일관되게 답변하고, 도구 호출 키워드도 도메인에 맞춰 선택하세요.\n\n"
        f"{_SYSTEM_PROMPT}"
    )


async def _call_llm(messages: list, with_tools: bool = True):
    """OpenAI tool calling 호출. 실패 시 None 반환."""
    kwargs = {"model": _MODEL, "messages": messages}
    if with_tools:
        kwargs["tools"]       = TOOLS
        kwargs["tool_choice"] = "auto"
    try:
        return await asyncio.to_thread(_client.chat.completions.create, **kwargs)
    except Exception:
        logger.exception("LLM 호출 실패")
        return None


def _tool_error(tool_call_id: str, message: str) -> dict:
    """도구 호출 실패를 LLM에게 전달할 메시지 형태로 만든다."""
    return {
        "role": "tool",
        "tool_call_id": tool_call_id,
        "content": json.dumps({"error": message}, ensure_ascii=False),
    }


async def run(question: str, project_context: str = "") -> str:
    """자연어 질문을 받아 tool calling 루프로 답변을 생성해 반환.
    project_context가 주어지면 시스템 프롬프트에 도메인 정보를 주입한다.
    """
    messages: list = [
        {"role": "system", "content": _build_system_prompt(project_context)},
        {"role": "user",   "content": question},
    ]
    seen_calls: set[tuple[str, str]] = set()  # (tool_name, args_json) 중복 호출 가드

    for iteration in range(_MAX_ITERATIONS):
        response = await _call_llm(messages, with_tools=True)
        if response is None:
            return _LLM_FAILURE_ANSWER

        message = response.choices[0].message

        # tool_calls 없음 → 최종 텍스트 답변
        if not message.tool_calls:
            return message.content or _FALLBACK_ANSWER

        messages.append(message)

        for tc in message.tool_calls:
            tool_name = tc.function.name

            # 인자 JSON 파싱 — 실패 시 LLM이 다음 iteration에서 교정할 수 있게 명확히 알림
            try:
                args = json.loads(tc.function.arguments)
            except json.JSONDecodeError:
                logger.warning("도구 인자 JSON 파싱 실패: %s", tool_name)
                messages.append(_tool_error(
                    tc.id,
                    f"{tool_name} 인자 JSON 파싱 실패. 올바른 JSON 형식으로 다시 호출하세요.",
                ))
                continue

            # 중복 호출 가드 — 같은 인자로 같은 도구를 반복 호출하면 비용/컨텍스트 낭비
            call_key = (tool_name, json.dumps(args, sort_keys=True, ensure_ascii=False))
            if call_key in seen_calls:
                logger.info("중복 도구 호출 차단: %s args=%s", tool_name, args)
                messages.append(_tool_error(
                    tc.id,
                    f"{tool_name}을(를) 동일한 인자로 이미 호출했습니다. 이전 결과를 참고하거나 다른 인자/도구를 시도하세요.",
                ))
                continue
            seen_calls.add(call_key)

            logger.info("도구 호출: %s", tool_name)
            result_str = await execute(tool_name, args)
            logger.debug("도구 결과: %s → %d자", tool_name, len(result_str))

            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": result_str,
            })

    logger.warning("최대 반복 횟수(%d) 도달 — 부분 답변 요청", _MAX_ITERATIONS)
    messages.append({
        "role": "user",
        "content": "지금까지 수집한 정보를 바탕으로 최종 답변을 작성해주세요.",
    })
    response = await _call_llm(messages, with_tools=False)
    if response is None:
        return _LLM_FAILURE_ANSWER
    return response.choices[0].message.content or _FALLBACK_ANSWER
