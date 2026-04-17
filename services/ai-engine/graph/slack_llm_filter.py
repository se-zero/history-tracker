import json
import os

from openai import OpenAI

client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])

SYSTEM_PROMPT = """\
당신은 팀의 지식 그래프 구축을 위해 슬랙 메시지를 분류하는 도우미입니다.
이 프로젝트는 GitHub, Jira, Slack 데이터를 연동하여 지식 그래프를 만드는 캡스톤 프로젝트입니다.

아래 메시지 중 지식 그래프에 저장할 가치가 있는 메시지의 번호만 JSON으로 반환하세요.

보존 기준 (다음 중 하나라도 해당되면 보존):
- 기술적 결정, 설계 논의, 아키텍처 방향
- 버그 원인 파악, 해결 방법, 트레이드오프
- 작업 분담, 역할 정의, 마일스톤
- 기능 요구사항, 개선 제안, 검토 의견 (구체적인 내용 포함)

제거 기준 (다음 중 하나라도 해당되면 제거):
- "approve 했어", "머지 완료", "확인할게", "오케", "알겠어" 등 단순 행위 확인
- 다른 프로젝트나 다른 수업 관련 내용 (이 프로젝트와 무관한 서버, DB, 스터디 오류 등)
- "시간될 때 확인해줘"처럼 내용 없이 요청만 있는 메시지
- 단순 감사, 인사, 잡담

출력 형식 (다른 텍스트 없이 JSON만):
{"keep": [0, 2, 3]}
"""


def filter_messages(messages: list[str]) -> list[bool]:
    """
    messages: 메시지 body 문자열 리스트
    반환: 각 메시지의 보존 여부 (True=보존, False=제거)
    """
    if not messages:
        return []

    numbered = "\n".join(f"[{i}] {msg}" for i, msg in enumerate(messages))

    response = client.chat.completions.create(
        model="gpt-4o-mini",
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": numbered},
        ],
        temperature=0,
    )

    result = json.loads(response.choices[0].message.content.strip())
    keep_indices = set(result.get("keep", []))
    return [i in keep_indices for i in range(len(messages))]
