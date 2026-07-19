"""방안 D 공통 — 쌍의 관련도를 LLM이 판정한다 (변형 1·2 공유).

변형 1(D-추천: 임베딩이 추천 → LLM이 선택)과 변형 2(D-필터: A가 확정 → LLM이 검수)는
"무엇을 판정 대상으로 삼느냐"만 다르고 판정 행위 자체는 같다. 판정기를 한 곳에 두어야
두 변형의 측정 델타가 프롬프트 차이로 오염되지 않는다.

실패 전파 정책 (orchestrator와 동일):
  설정 오류(4xx — 모델명 오타·인증 실패·미지원 파라미터)는 전파한다. 삼키면 모든 쌍이
  confidence 0.0으로 떨어져 "LLM이 전부 무관하다고 판정했다"는 측정 결과로 위장된다.
  일시 오류(429·5xx·연결 실패)는 그 쌍만 스킵하고 JudgeStats에 집계한다 — 스킵된 쌍이
  몇 개인지 모르면 recall 하락이 판정 탓인지 장애 탓인지 구분할 수 없다.
"""

import json
import logging
from dataclasses import dataclass

from openai_client import Priority, chat_completion

logger = logging.getLogger(__name__)

JUDGE_MODEL = "gpt-4o-mini"
# 이 값 이상만 엣지로 인정한다 (계획서 Phase 4 고정값).
DEFAULT_LLM_THRESHOLD = 0.7

_MAX_TEXT_LEN = 800

# 재시도로 회복되지 않는 클라이언트 오류 — orchestrator._UNRECOVERABLE_STATUS와 같은 정책.
_UNRECOVERABLE_STATUS = {400, 401, 403, 404, 422}


@dataclass
class JudgeStats:
    """판정 호출 집계 — 측정 기록 4항목 중 'run당 LLM 호출 수'와 실패 가시화를 겸한다."""

    judged: int = 0    # 판정이 성공한 쌍 (= 실제 LLM 호출 성공 수)
    skipped: int = 0   # 일시 오류로 판정하지 못한 쌍

    @property
    def calls(self) -> int:
        return self.judged + self.skipped

    def summary(self) -> str:
        return f"judged={self.judged} skipped={self.skipped} calls={self.calls}"


def _is_unrecoverable(exc: Exception) -> bool:
    return getattr(exc, "status_code", None) in _UNRECOVERABLE_STATUS


def _truncate(text: str) -> str:
    return text[:_MAX_TEXT_LEN] + "..." if len(text) > _MAX_TEXT_LEN else text


def format_commit_text(message: str, diff_summary: str) -> str:
    """LLM에게 보여줄 커밋 원문 — 커밋 메시지 + diff 요약.

    메시지를 넣는 이유(Phase 4 통제 조건): 중간 측정이 기각된 원인이 "임베딩은 메시지를 보는데
    LLM은 diff만 봤다"는 입력 불일치였다. 두 변형 모두 같은 실명(實名)을 보게 맞춘다.
    """
    parts = []
    if message.strip():
        parts.append(f"커밋 메시지: {_truncate(message.strip())}")
    if diff_summary.strip():
        parts.append(f"변경 요약: {_truncate(diff_summary.strip())}")
    return "\n".join(parts)


async def judge_pair(
    left_label: str,
    left_text: str,
    right_label: str,
    right_text: str,
    project_context: str = "",
    stats: JudgeStats | None = None,
) -> float | None:
    """두 텍스트가 실제로 관련 있는지 LLM이 판정. confidence(0.0~1.0) 반환.

    Returns:
        float — 판정 성공
        None  — 일시 오류/파싱 실패로 판정 불가 (호출자는 이 쌍을 스킵한다. 0.0과 다르다:
                0.0은 "LLM이 무관하다고 답했다"이고 None은 "판정하지 못했다"이다)

    Raises:
        설정 오류(4xx) — 전파해서 측정 자체를 멈춘다.
    """
    context_block = (
        f"[프로젝트 컨텍스트]\n{project_context.strip()}\n\n" if project_context.strip() else ""
    )
    prompt = (
        f"{context_block}"
        f"다음 {left_label}와(과) {right_label}이(가) 실제로 관련이 있는지 판단해주세요.\n\n"
        f"{left_label}:\n{_truncate(left_text)}\n\n"
        f"{right_label}:\n{_truncate(right_text)}\n\n"
        f'JSON 형식으로만 응답: {{"confidence": 0.8, "reason": "한 줄 이유"}}\n'
        f"confidence는 0.0~1.0 (1.0: 직접 관련, 0.5: 간접 관련, 0.0: 무관)"
    )

    try:
        resp = await chat_completion(
            priority=Priority.BACKGROUND,
            model=JUDGE_MODEL,
            messages=[{"role": "user", "content": prompt}],
            response_format={"type": "json_object"},
            temperature=0,
        )
    except Exception as exc:
        if _is_unrecoverable(exc):
            raise
        logger.warning("LLM 판정 일시 실패 — 이 쌍은 스킵: %s", exc)
        if stats:
            stats.skipped += 1
        return None

    try:
        confidence = float(json.loads(resp.choices[0].message.content).get("confidence", 0.0))
    except (json.JSONDecodeError, TypeError, ValueError, AttributeError, IndexError) as exc:
        logger.warning("LLM 판정 응답 파싱 실패 — 이 쌍은 스킵: %s", exc)
        if stats:
            stats.skipped += 1
        return None

    if stats:
        stats.judged += 1
    return confidence
