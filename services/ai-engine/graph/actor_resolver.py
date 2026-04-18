"""
Actor 동일인 판단 파이프라인.

새로운 actor.id가 도착하면 아래 순서로 동일인을 판단한다:
  Step 0: alias 조회        → 이미 아는 actor면 즉시 반환 (O(1), 비용 0)
  Step 1: email 정확 매칭   → 확실한 동일인 (비용 최소)
  Step 2: 이름 스코어링     → score < 0.4 → Step 4, score ≥ 0.4 → Step 3
  Step 3: LLM 다중 신호     → confidence ≥ 0.85 → MERGE, 미달 → Step 4
  Step 4: 신규 Actor 생성   → 판단 불가 시 fallback

Neo4j 직접 호출 없이 ActorStore 인터페이스로 주입받아 테스트 가능하게 설계.
"""

import logging
import re
from dataclasses import dataclass
from difflib import SequenceMatcher
from typing import Awaitable, Callable, Optional

from graph.actor_llm import judge_same_person

logger = logging.getLogger(__name__)


@dataclass
class ActorStore:
    """Neo4j Actor 조회·생성 함수 묶음. 테스트 시 mock으로 교체 가능."""

    lookup_by_alias: Callable[[str], Awaitable[Optional[dict]]]
    """source-scoped alias(예: 'GITHUB:se-zero')로 Actor 조회. 없으면 None."""

    lookup_by_email: Callable[[str], Awaitable[Optional[dict]]]
    """email 정확 매칭으로 Actor 조회. emails 배열에 포함되면 반환. 없으면 None."""

    lookup_by_name: Callable[[str], Awaitable[list[dict]]]
    """정규화된 이름으로 Actor 후보 목록 반환.
    Args:
        normalized_name: normalize_name() 결과 (예: 'johndoe', '김철수')
    Returns:
        정규화 이름이 일치하는 Actor dict 목록 (없으면 [])
    """

    lookup_activities: Callable[[dict], Awaitable[list[dict]]]
    """기존 Actor의 최근 활동 내역 반환 (Step 3 LLM 컨텍스트용).
    Args:
        actor: 기존 Actor 노드 dict
    Returns:
        활동 dict 목록 — 각 항목: {"source", "nodeType", "title", "message", "body"}
    """

    merge_actor: Callable[[dict, str, Optional[str], float], Awaitable[None]]
    """기존 Actor에 새 alias(·email)를 추가한다.
    Args:
        actor:      기존 Actor 노드 dict (lookup 결과)
        new_alias:  추가할 source-scoped alias (예: 'GITHUB:se-zero')
        new_email:  추가할 이메일 (이미 있으면 no-op, None이면 생략)
        confidence: 통합 신뢰도 (email 정확 매칭 → 1.0, LLM 판단 → LLM 반환값)
    """

    create_actor: Callable[[str, list, list, float], Awaitable[dict]]
    """신규 Actor 노드를 생성하고 반환한다.
    Args:
        name:       표시 이름
        aliases:    source-scoped alias 목록 (예: ['GITHUB:se-zero'])
        emails:     확인된 이메일 목록 (없으면 [])
        confidence: 통합 신뢰도 (새 노드 생성 시 1.0)
    """


def normalize_name(name: str) -> str:
    """이름에서 공백·특수문자를 제거하고 소문자로 변환.

    Examples:
        "John Doe"       → "johndoe"
        "김철수 (BE)"    → "김철수"
        "se-zero"        → "sezero"
    """
    return re.sub(r"[^a-z0-9가-힣]", "", name.lower().strip())


def _score_candidate(new_email: Optional[str], candidate: dict) -> float:
    """기존 Actor 후보와의 매칭 점수를 계산한다.

    lookup_by_name으로 찾은 후보는 이름이 이미 매칭됨 → base score 0.5.

    Score 구성:
        +0.5        이름 정규화 매칭 (lookup_by_name 조건이므로 항상 적용)
        +ratio*0.5  이메일 로컬파트 유사 (SequenceMatcher ratio, 최대 +0.5)
    """
    score = 0.5
    candidate_emails = candidate.get("emails") or []
    if not new_email or not candidate_emails:
        return score

    new_local = new_email.split("@")[0] if "@" in new_email else ""
    candidate_locals = [e.split("@")[0] for e in candidate_emails if "@" in e]
    best_ratio = max(
        (SequenceMatcher(None, new_local, lp).ratio() for lp in candidate_locals),
        default=0.0,
    )
    score += best_ratio * 0.5

    return score


async def resolve_actor(actor: dict, source: str, store: ActorStore, event: Optional[dict] = None) -> dict:
    """NormalizedEvent의 actor 정보로 Neo4j Actor 노드를 찾거나 생성한다.

    Args:
        actor:  NormalizedEvent의 actor 필드
                {"id": "se-zero", "name": "John Doe", "email": "john@example.com"}
        source: 이벤트 출처 — "GITHUB" | "JIRA" | "SLACK"
        store:  Neo4j 조회·생성 함수 묶음
        event:  전체 NormalizedEvent (Step 3 LLM 컨텍스트용, None이면 빈 dict 처리)

    Returns:
        매칭되거나 생성된 Actor 노드 dict
    """
    event = event or {}
    actor_id  = actor.get("id")
    name      = actor.get("name") or actor_id or "unknown"
    email     = actor.get("email")          # null 허용 — pipeline-worker 수집 불가 시
    source_id = f"{source}:{actor_id}"      # e.g. "GITHUB:se-zero"

    # ── Step 0: alias 조회 ────────────────────────────────────────────────
    # 이미 등록된 alias면 즉시 반환 — 이후 단계 비용 없음
    existing = await store.lookup_by_alias(source_id)
    if existing:
        logger.debug("[Step 0] alias 매칭: %s → Actor(%s)", source_id, existing.get("name"))
        return existing

    # ── Step 1: email 정확 매칭 ───────────────────────────────────────────
    # pipeline-worker가 가져온 email이 기존 Actor의 emails 배열에 있으면 동일인
    if email:
        existing = await store.lookup_by_email(email)
        if existing:
            logger.info(
                "[Step 1] email 매칭: %s → Actor(%s), alias 추가: %s",
                email, existing.get("name"), source_id,
            )
            await store.merge_actor(existing, source_id, email, 1.0)
            return existing

    # ── Step 2: 이름 정규화 + email 교차 스코어링 ─────────────────────────
    normalized_new_name = normalize_name(name)
    candidates = await store.lookup_by_name(normalized_new_name)

    if candidates:
        best_candidate = max(candidates, key=lambda c: _score_candidate(email, c))
        best_score = _score_candidate(email, best_candidate)
        logger.debug(
            "[Step 2] 후보 %d명, best_score=%.2f, candidate=%s",
            len(candidates), best_score, best_candidate.get("name"),
        )

        if best_score >= 0.4:
            # ── Step 3: LLM 다중 신호 판단 ──────────────────────────────────
            activities = await store.lookup_activities(best_candidate)
            result = await judge_same_person(best_candidate, activities, actor, source, event)
            logger.info(
                "[Step 3] LLM 판단: same=%s, confidence=%.2f, signals=%s",
                result["same_person"], result["confidence"], result.get("key_signals"),
            )

            if result["same_person"] and result["confidence"] >= 0.85:
                await store.merge_actor(best_candidate, source_id, email, result["confidence"])
                return best_candidate

    # ── Step 4: 신규 Actor 노드 생성 ─────────────────────────────────────
    emails = [email] if email else []
    new_actor = await store.create_actor(name, [source_id], emails, 1.0)
    logger.info(
        "[Step 4] 신규 Actor 생성: name=%s, alias=%s, emails=%s",
        name, source_id, emails,
    )
    return new_actor
