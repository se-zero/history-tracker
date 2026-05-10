import asyncio
import json
import logging
import os

from openai import OpenAI

from tools.definitions import TOOLS
from tools.executor import execute

logger = logging.getLogger(__name__)

_client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])
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

[도구 사용 가이드]
- 커밋 hash나 Jira key를 모를 때: search_by_keyword로 진입점 탐색 후 다른 도구 호출
- 코드 변경 이유: search_by_keyword → get_changeset_context
- Jira 이슈 중심 탐색: get_issue_context 또는 get_timeline
- 파일 담당자: find_expert
- 사람 활동 조회: get_actor_activity
- 컨텍스트 없는 커밋: check_missing_context
- 여러 출처 비교: get_conflict_context
"""


async def run(question: str) -> str:
    """자연어 질문을 받아 tool calling 루프로 답변을 생성해 반환."""
    messages = [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": question},
    ]

    for iteration in range(_MAX_ITERATIONS):
        response = await asyncio.to_thread(
            _client.chat.completions.create,
            model=_MODEL,
            messages=messages,
            tools=TOOLS,
            tool_choice="auto",
        )

        message = response.choices[0].message

        # tool_calls 없음 → 최종 텍스트 답변
        if not message.tool_calls:
            return message.content or "답변을 생성하지 못했습니다."

        # tool_calls 실행
        messages.append(message)

        tool_results = []
        for tc in message.tool_calls:
            tool_name = tc.function.name
            try:
                args = json.loads(tc.function.arguments)
            except json.JSONDecodeError:
                args = {}

            logger.info("도구 호출: %s args=%s", tool_name, args)
            result_str = await execute(tool_name, args)
            logger.debug("도구 결과: %s → %s", tool_name, result_str[:200])

            tool_results.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": result_str,
            })

        messages.extend(tool_results)

    logger.warning("최대 반복 횟수(%d) 도달 — 부분 답변 반환", _MAX_ITERATIONS)
    # 마지막으로 tool 없이 최종 답변 요청
    messages.append({
        "role": "user",
        "content": "지금까지 수집한 정보를 바탕으로 최종 답변을 작성해주세요.",
    })
    response = await asyncio.to_thread(
        _client.chat.completions.create,
        model=_MODEL,
        messages=messages,
    )
    return response.choices[0].message.content or "답변을 생성하지 못했습니다."
