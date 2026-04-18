import asyncio
import json
import logging
import os

from openai import OpenAI

logger = logging.getLogger(__name__)

client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])

SYSTEM_PROMPT = """\
당신은 협업 툴 데이터를 분석하는 전문가입니다.
두 사용자가 동일인인지 판단하고 반드시 JSON 형식으로만 응답하세요.
JSON 이외의 텍스트는 절대 포함하지 마세요.
"""


async def judge_same_person(
    existing_actor: dict,
    activities: list[dict],
    new_actor: dict,
    source: str,
    event: dict,
) -> dict:
    """두 actor가 동일인인지 LLM에 판단 요청.

    Args:
        existing_actor: 기존 Actor 노드 dict (name, aliases, emails 포함)
        activities:     기존 Actor의 최근 활동 내역 (lookup_activities 결과)
        new_actor:      NormalizedEvent의 actor 필드 {"id", "name", "email"}
        source:         신규 이벤트 출처 — "GITHUB" | "JIRA" | "SLACK"
        event:          전체 NormalizedEvent (nodeType, properties 등)

    Returns:
        {"same_person": bool, "confidence": float,
         "key_signals": list[str], "reason": str}
        파싱 실패 시: {"same_person": False, "confidence": 0.0, ...}
    """
    prompt = _build_prompt(existing_actor, activities, new_actor, source, event)
    raw = await asyncio.to_thread(_call_llm, prompt)
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        logger.error("LLM 응답 JSON 파싱 실패: %r", raw[:200])
        return {
            "same_person": False,
            "confidence": 0.0,
            "key_signals": [],
            "reason": "LLM 응답 JSON 파싱 실패",
        }


def _call_llm(prompt: str) -> str:
    """OpenAI API 동기 호출. asyncio.to_thread()로 감싸서 사용."""
    response = client.chat.completions.create(
        model="gpt-4o-mini",
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user",   "content": prompt},
        ],
        temperature=0,
    )
    return response.choices[0].message.content.strip()


def _build_prompt(
    existing_actor: dict,
    activities: list[dict],
    new_actor: dict,
    source: str,
    event: dict,
) -> str:
    """docs/actor-node-design.md Step 3 스펙의 프롬프트를 구성한다."""
    activity_lines = "\n".join(
        f"  · [{a.get('source', '?')} {a.get('nodeType', '?')}] "
        f"{a.get('title') or a.get('message') or a.get('body') or '(내용 없음)'}"
        for a in activities[:10]
    ) or "  (활동 내역 없음)"

    props = event.get("properties") or {}
    node_type     = event.get("nodeType", "?")
    current_title = props.get("title") or props.get("pr_title") or "없음"
    current_body  = props.get("body")  or props.get("message")  or "없음"

    new_email     = new_actor.get("email") or "없음"
    new_name      = new_actor.get("name")  or new_actor.get("id") or "알 수 없음"

    return f"""다음 두 사용자가 동일인인지 판단해주세요.

[사용자 A — 기존 등록된 Actor]
- 이름: {existing_actor.get("name")}
- 이메일들: {existing_actor.get("emails") or []}
- 플랫폼: {existing_actor.get("aliases") or []}
- 최근 활동 내용:
{activity_lines}

[사용자 B — 신규 이벤트의 Actor]
- 이름: {new_name}
- 이메일: {new_email}
- 플랫폼: {source}
- 이번 이벤트 내용:
  · [{source} {node_type}]
    제목: {current_title}
    본문: {current_body}

판단 기준 (중요도 순):
1. 이름의 한/영 표기 변형, 성/이름 순서 역전, 닉네임 패턴 (john-doe↔john_doe, 대소문자, 구분자 차이 포함)
2. 이메일 로컬파트 유사도
3. 활동 내용의 도메인·기술 스택·관심사가 겹치는가
4. 같은 플랫폼 생태계(회사 도메인, 같은 채널/레포)에 속하는가

활동 내용이 없거나 너무 일반적이면 이름·이메일 신호에만 의존하고 confidence를 낮게 설정해주세요.

JSON으로만 응답 (다른 텍스트 없이):
{{"same_person": true 또는 false, "confidence": 0.0~1.0, "key_signals": ["판단 근거 1", "판단 근거 2"], "reason": "한 문장 설명"}}"""
