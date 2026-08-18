import json
import logging

from openai_client import Priority, chat_completion

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """\
당신은 협업 툴 데이터를 분석하는 전문가입니다.
두 사용자가 동일인인지 판단하고 반드시 JSON 형식으로만 응답하세요.
JSON 이외의 텍스트는 절대 포함하지 마세요.
"""

_FAILED_RESULT = {
    "same_person": False,
    "confidence": 0.0,
    "key_signals": [],
    "reason": "LLM 호출 또는 응답 파싱 실패",
}


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
        source:         신규 이벤트 출처 — 대문자 소스 식별자 (예: "GITHUB", "JIRA", "LINEAR")
        event:          전체 NormalizedEvent (nodeType, properties 등)

    Returns:
        {"same_person": bool, "confidence": float,
         "key_signals": list[str], "reason": str}
        호출/파싱 실패 시 _FAILED_RESULT 반환 (same_person=False).
    """
    prompt = _build_prompt(existing_actor, activities, new_actor, source, event)
    raw = await _call_llm(prompt)
    if raw is None:
        return dict(_FAILED_RESULT)
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        logger.error("LLM 응답 JSON 파싱 실패: %r", raw[:200])
        return dict(_FAILED_RESULT)


async def _call_llm(prompt: str) -> str | None:
    """OpenAI API 호출 (rate-limited 게이트웨이 경유).
    API 실패 시 None 반환 — 이벤트 처리가 단일 호출 실패로 중단되지 않도록.
    """
    try:
        response = await chat_completion(
            priority=Priority.BACKGROUND,
            model="gpt-4o-mini",
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": prompt},
            ],
            temperature=0,
        )
        return response.choices[0].message.content
    except Exception:
        logger.exception("LLM 호출 실패")
        return None


def _format_activity(a: dict) -> str:
    """활동 한 건을 LLM 입력용 한 줄로 포맷한다.
    날짜·채널·제목/메시지를 포함해 시간 신호와 도메인 신호를 살린다.
    """
    occurred_at = a.get("occurred_at")
    date_str = occurred_at.date().isoformat() if occurred_at else "?"

    channel = a.get("channel")
    channel_str = f" #{channel}" if channel else ""

    text = a.get("title") or a.get("message") or a.get("body") or "(내용 없음)"
    text = text.strip().replace("\n", " ")[:80]

    return f"  · {date_str} [{a.get('source', '?')} {a.get('nodeType', '?')}]{channel_str} {text}"


def _build_prompt(
    existing_actor: dict,
    activities: list[dict],
    new_actor: dict,
    source: str,
    event: dict,
) -> str:
    """docs/actor-node-design.md Step 3 스펙의 프롬프트를 구성한다."""
    activity_lines = "\n".join(_format_activity(a) for a in activities[:10]) or "  (활동 내역 없음)"

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
2. 이메일 로컬파트 유사도 및 동일 도메인 여부
3. 활동 시기·도메인·기술 스택·관심사가 겹치는가 (시간이 가까울수록 강한 신호)
4. 같은 플랫폼 생태계(회사 도메인, 같은 채널/레포)에 속하는가

활동 내용이 없거나 너무 일반적이면 이름·이메일 신호에만 의존하고 confidence를 낮게 설정해주세요.

⚠️ 한국 이름은 흔한 성씨/이름 조합이 많아 동명이인이 자주 발생합니다.
이름이 같더라도 활동 도메인·시기·이메일 도메인 중 둘 이상의 독립 신호가 일치하지 않으면 confidence를 0.7 미만으로 낮추세요.

[판단 예시]
사용자 A: name="John Doe", emails=["jdoe@acme.com"], aliases=["GITHUB:john-doe"], 최근 활동: 2026-04-10 [GITHUB ChangeSet] auth 모듈 리팩토링
사용자 B: name="존 도", email="john.doe@acme.com", platform="JIRA", 이벤트: auth 권한 이슈
판단: same_person=true, confidence=0.9 (한영 변형 + 동일 회사 도메인 + 같은 도메인 활동)

사용자 A: name="김철수", emails=[], aliases=["SLACK:U123"], 최근 활동: (활동 내역 없음)
사용자 B: name="김철수", email=null, platform="GITHUB", 이벤트: typo 수정
판단: same_person=false, confidence=0.3 (이름만 같고 이메일·활동 신호 전무)

JSON으로만 응답 (다른 텍스트 없이):
{{"same_person": true 또는 false, "confidence": 0.0~1.0, "key_signals": ["판단 근거 1", "판단 근거 2"], "reason": "한 문장 설명"}}"""
