"""
Actor 동일인 판단 파이프라인.

새로운 actor.id가 도착하면 아래 순서로 동일인을 판단한다:
  Step 0: alias 조회        → 이미 아는 actor면 즉시 반환 (O(1), 비용 0)
  Step 1: email 정확 매칭   → 확실한 동일인 (비용 최소)
  Step 2: 이름 스코어링     → score < 0.4 → Step 4, score ≥ 0.4 → Step 3
  Step 3: LLM 다중 신호     → confidence ≥ 0.9 → MERGE, 미달 → Step 4
  Step 4: 신규 Actor 생성   → 판단 불가 시 fallback

수동 distinct 결정(docs/actor-manual-merge.md)이 금지한 Actor는 Step 1 매칭과
Step 2 후보에서 제외된다 — 수동 결정이 자동 판단을 이긴다.

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
    """source-scoped alias(예: 'GITHUB:se-zero')로 Actor 조회. 없으면 None.
    반환 dict에는 Actor 필드(uuid·name·aliases)와 함께 그 alias가 들고 있는
    개인정보 상태 alias_pd_name, alias_pd_erased가 포함된다 (Step 0 이름 갱신 판단용).
    """

    lookup_by_email: Callable[[str], Awaitable[Optional[dict]]]
    """email 정확 매칭으로 Actor 조회. 어느 alias든 pd_email이 일치하면 반환. 없으면 None.
    반환 dict의 emails는 그 Actor의 전체 alias에서 모은 pd_email 목록(중복 제거)이다.
    """

    lookup_by_name: Callable[[str], Awaitable[list[dict]]]
    """정규화된 이름으로 Actor 후보 목록 반환.
    Args:
        normalized_name: normalize_name() 결과 (예: 'johndoe', '김철수')
    Returns:
        정규화 이름이 일치하는 Actor dict 목록 (없으면 []).
        각 dict의 emails는 그 Actor의 전체 alias에서 모은 pd_email 목록(Step 2 스코어링 재료).
    """

    lookup_activities: Callable[[dict], Awaitable[list[dict]]]
    """기존 Actor의 최근 활동 내역 반환 (Step 3 LLM 컨텍스트용).
    Args:
        actor: 기존 Actor 노드 dict
    Returns:
        활동 dict 목록 — 각 항목: {"source", "nodeType", "title", "message", "body"}
    """

    merge_actor: Callable[[dict, str, Optional[str], str], Awaitable[None]]
    """기존 Actor에 새 alias(·email·이름)를 추가한다.
    Args:
        actor:      기존 Actor 노드 dict (lookup 결과)
        new_alias:  추가할 source-scoped alias (예: 'GITHUB:se-zero')
        new_email:  이 계정에서 받은 이메일 (없으면 None) — ActorAlias.pd_email로 저장
        new_name:   이 계정에서 받은 이름 — ActorAlias.pd_name으로 저장, 표시 이름 재계산에 반영
    """

    create_actor: Callable[[str, str, Optional[str]], Awaitable[dict]]
    """신규 Actor 노드를 생성하고 반환한다.
    Args:
        name:      표시 이름 (첫 alias의 pd_name)
        source_id: source-scoped alias (예: 'GITHUB:se-zero')
        email:     확인된 이메일 (없으면 None)
    """

    lookup_vetoes: Optional[Callable[[str], Awaitable[list[str]]]] = None
    """수동 distinct 결정으로 이 source_id와 병합이 금지된 Actor uuid 목록.
    None이면 결정 저장소 없음(기존 mock/호출부 호환) — 거부 없이 동작한다.
    설계: docs/actor-manual-merge.md
    """

    update_alias_name: Optional[Callable[[str, str], Awaitable[None]]] = None
    """이미 아는 alias의 이름을 갱신한다 (Step 0 — 표시 이름이 첫 수집 값에 고정되는 것을 막는다).
    Args:
        source_id: 갱신 대상 alias (예: 'GITHUB:se-zero')
        name:      이번 이벤트에서 온 이름
    None이면 갱신 없음(기존 mock/호출부 호환).
    """


def normalize_name(name: str) -> str:
    """이름에서 공백·특수문자를 제거하고 소문자로 변환.

    Examples:
        "John Doe"       → "johndoe"
        "김철수 (BE)"    → "김철수"
        "se-zero"        → "sezero"
    """
    return re.sub(r"[^a-z0-9가-힣]", "", name.lower().strip())


_MAX_LLM_CANDIDATES = 3


def _score_candidate(new_email: Optional[str], candidate: dict) -> float:
    """기존 Actor 후보와의 매칭 점수를 계산한다.

    lookup_by_name으로 찾은 후보는 이름이 이미 매칭됨 → base score 0.5.

    Score 구성:
        +0.5         이름 정규화 매칭 (lookup_by_name 조건이므로 항상 적용)
        +ratio*0.3   이메일 로컬파트 유사도 (SequenceMatcher ratio)
        +0.2         이메일 도메인 일치 (동일 조직 신호)
    """
    score = 0.5
    candidate_emails = candidate.get("emails") or []
    if not new_email or "@" not in new_email or not candidate_emails:
        return score

    new_local, _, new_domain = new_email.partition("@")
    candidate_locals  = [e.split("@")[0] for e in candidate_emails if "@" in e]
    candidate_domains = {e.split("@")[1] for e in candidate_emails if "@" in e}

    best_ratio = max(
        (SequenceMatcher(None, new_local, lp).ratio() for lp in candidate_locals),
        default=0.0,
    )
    score += best_ratio * 0.3

    if new_domain in candidate_domains:
        score += 0.2

    return min(score, 1.0)


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
        # 이름 갱신 — 표시 이름이 첫 수집 값에 영구히 고정되는 것을 막는다.
        # "closed"(계정 폐쇄로 삭제)는 절대 갱신하지 않는다 — 자동 경로로 되살아나는 것을
        # 막는 유일한 방어선이다. "access_lost"(재조회 불가로 삭제)는 재연동 복구 경로라 갱신한다.
        if (
            store.update_alias_name
            and name != existing.get("alias_pd_name")
            and existing.get("alias_pd_erased") != "closed"
        ):
            await store.update_alias_name(source_id, name)
        return existing

    # ── 수동 분리 결정(veto) 조회 ─────────────────────────────────────────
    # distinct 결정이 금지한 Actor로는 Step 1/3의 자동 병합을 하지 않는다
    # (수동 결정이 자동 판단을 이긴다 — docs/actor-manual-merge.md)
    vetoed: set = set()
    if store.lookup_vetoes:
        vetoed = set(await store.lookup_vetoes(source_id) or [])

    # ── Step 1: email 정확 매칭 ───────────────────────────────────────────
    # pipeline-worker가 가져온 email이 기존 Actor의 emails 배열에 있으면 동일인
    if email:
        existing = await store.lookup_by_email(email)
        if existing:
            if existing.get("uuid") in vetoed:
                logger.info(
                    "[Step 1] email 매칭이 수동 분리 결정으로 차단됨: %s ↛ Actor(%s)",
                    source_id, existing.get("name"),
                )
            else:
                logger.info(
                    "[Step 1] email 매칭: %s → Actor(%s), alias 추가: %s",
                    email, existing.get("name"), source_id,
                )
                await store.merge_actor(existing, source_id, email, name)
                return existing

    # ── Step 2: 이름 정규화 + email 교차 스코어링 ─────────────────────────
    normalized_new_name = normalize_name(name)
    candidates = await store.lookup_by_name(normalized_new_name)
    candidates = [c for c in candidates if c.get("uuid") not in vetoed]

    if candidates:
        scored = sorted(
            ((_score_candidate(email, c), c) for c in candidates),
            key=lambda x: x[0],
            reverse=True,
        )
        logger.debug(
            "[Step 2] 후보 %d명, top scores=%s",
            len(candidates), [f"{s:.2f}" for s, _ in scored[:_MAX_LLM_CANDIDATES]],
        )

        # ── Step 3: 상위 후보들에 대해 LLM 다중 신호 판단 (max K명) ──────
        for score, candidate in scored[:_MAX_LLM_CANDIDATES]:
            if score < 0.4:
                break
            activities = await store.lookup_activities(candidate)
            result = await judge_same_person(candidate, activities, actor, source, event)
            logger.info(
                "[Step 3] LLM 판단: candidate=%s same=%s confidence=%.2f signals=%s",
                candidate.get("name"), result["same_person"], result["confidence"], result.get("key_signals"),
            )

            if result["same_person"] and result["confidence"] >= 0.9:
                await store.merge_actor(candidate, source_id, email, name)
                return candidate

    # ── Step 4: 신규 Actor 노드 생성 ─────────────────────────────────────
    new_actor = await store.create_actor(name, source_id, email)
    logger.info(
        "[Step 4] 신규 Actor 생성: name=%s, alias=%s, email=%s",
        name, source_id, email,
    )
    return new_actor
