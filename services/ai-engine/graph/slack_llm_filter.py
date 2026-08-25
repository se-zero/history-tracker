import json

from openai_client import Priority, chat_completion

_SHARED_CRITERIA = """\
보존 기준 (다음 중 하나라도 해당되면 보존):
- 기술적 결정, 설계 논의, 아키텍처 방향
- 버그 원인 파악, 해결 방법, 트레이드오프
- 작업 분담, 역할 정의, 마일스톤
- 기능 요구사항, 개선 제안, 검토 의견 (구체적인 내용 포함)
  예) "로그인 토큰 만료를 7일로 늘려야 함" → 보존
      "이거 괜찮을 것 같은데요" → 제거 (구체적 내용 없음)

제거 기준 (다음 중 하나라도 해당되면 제거):
- "approve 했어", "머지 완료", "확인할게", "오케", "알겠어" 등 단순 행위 확인
- 위 프로젝트 컨텍스트와 무관한 내용 (다른 프로젝트, 다른 수업, 관계없는 서버/DB 오류 등)
- "시간될 때 확인해줘"처럼 내용 없이 요청만 있는 메시지
- 단순 감사, 인사, 잡담

⚠️ 기준 충돌 시: 기술적 내용이 포함된 메시지는 요청 형식이더라도 보존합니다.
  예) "PR 리뷰해줘, 인덱스 추가했는데 N+1 이슈 있을 것 같아" → 보존
      "시간될 때 확인해줘" → 제거 (기술적 내용 없음)

[분류 예시 — 경계선 케이스]
입력: "DB 인덱스 추가해놨어. N+1 이슈 있을 것 같으니 확인 필요"
판단: 보존 (요청 + 기술 내용 → 기술 우선)

입력: "이번 주 회식 어디서 해?"
판단: 제거 (잡담, 프로젝트 무관)

입력: "그건 어제 회의에서 얘기한 대로 가자"
판단: 제거 (구체적 내용 없음, 외부 맥락 의존)\
"""

_THREAD_PROMPT = """\
당신은 팀의 지식 그래프 구축을 위해 슬랙 메시지를 분류하는 도우미입니다.

[프로젝트 컨텍스트]
GitHub, Jira, Slack 데이터를 연동하여 지식 그래프를 만드는 캡스톤 프로젝트입니다.

아래 메시지들은 하나의 슬랙 스레드에서 시간 순서대로 발생한 대화입니다.
앞뒤 맥락을 고려해 각 메시지의 보존 여부를 판단하세요.

{shared_criteria}

⚠️ 스레드 맥락: 앞 메시지가 기술 질문이고 뒤 메시지가 그 답변이라면 답변도 보존합니다.
  예) [0] "Redis 캐시 TTL 얼마로 설정했어?"
      [1] "24시간으로 했어" → [0]의 답변이므로 보존
      [2] "ㅇㅋ" → 단순 확인이므로 제거

출력 형식 (다른 텍스트 없이 JSON만):
{{"keep": [0, 2, 3]}}\
"""

_STANDALONE_PROMPT = """\
당신은 팀의 지식 그래프 구축을 위해 슬랙 메시지를 분류하는 도우미입니다.

[프로젝트 컨텍스트]
GitHub, Jira, Slack 데이터를 연동하여 지식 그래프를 만드는 캡스톤 프로젝트입니다.

아래 메시지들은 서로 독립적인 슬랙 메시지입니다. 각 메시지를 개별적으로 판단하세요.

{shared_criteria}

출력 형식 (다른 텍스트 없이 JSON만):
{{"keep": [0, 2, 3]}}\
"""


def build_prompt(is_thread: bool) -> str:
    template = _THREAD_PROMPT if is_thread else _STANDALONE_PROMPT
    return template.format(shared_criteria=_SHARED_CRITERIA)


async def filter_messages(messages: list[str], is_thread: bool = False) -> list[bool]:
    """
    messages: 메시지 body 문자열 리스트
    is_thread: True면 스레드 맥락 고려 프롬프트 사용
    반환: 각 메시지의 보존 여부 (True=보존, False=제거)
    """
    if not messages:
        return []

    numbered = "\n".join(f"[{i}] {msg}" for i, msg in enumerate(messages))

    response = await chat_completion(
        priority=Priority.BACKGROUND,
        model="gpt-4o-mini",
        response_format={"type": "json_object"},
        messages=[
            {"role": "system", "content": build_prompt(is_thread)},
            {"role": "user", "content": numbered},
        ],
        temperature=0,
    )

    result = json.loads(response.choices[0].message.content)
    keep_indices = set(result.get("keep", []))
    return [i in keep_indices for i in range(len(messages))]
