"""tools.queries 공용 — 모든 쿼리 모듈이 공유하는 드라이버/상수/헬퍼."""

import json
import os
from collections.abc import Callable

from graph.driver import get_driver


# ─── 2계층(detail/context) 반환 공용 헬퍼 ──────────────────────────────────────
# get_file_history에서 시작한 반환 정책 — detail(본문 포함 인용 대상)은 바이트 예산만큼
# 채우고 나머지는 stub 개요로 내린다. get_actor_activity 등 리스트 컷 도구가 공유한다.


def _priority_order(rows: list[dict], ranked: bool) -> list[dict]:
    """detail에 채울 우선순위 — ranked면 relevance desc(동점은 최신순), 아니면 입력 순서 유지."""
    if not ranked:
        return rows  # 호출부 쿼리가 이미 최신순(desc)으로 반환
    return sorted(
        rows,
        key=lambda r: (
            r["relevance"] if r.get("relevance") is not None else -1.0,
            r.get("occurredAt") or "",
        ),
        reverse=True,
    )


def _detail_count_for_budget(
    rows: list[dict],
    ranked: bool,
    budget_chars: int,
    k_max: int,
    render: Callable[[dict, bool], dict],
) -> int:
    """detail에 담을 행 수 — 우선순위 순으로 직렬화 크기를 누적하며 예산 안에서 최대한 채운다.

    다 담아도 예산에 맞으면 전량(=구 동작, 나열형 recall 보존), 넘치면 예산까지만.
    최소 1건은 보장하고 k_max로 하드 상한을 둔다. render는 실제 detail 행 렌더러 —
    예산 산정과 실제 반환이 같은 모양이어야 크기 계산이 정확하다.
    """
    used, k = 0, 0
    for r in _priority_order(rows, ranked):
        if k >= k_max:
            break
        size = len(json.dumps(render(r, ranked), ensure_ascii=False, default=str))
        if k > 0 and used + size > budget_chars:
            break
        used += size
        k += 1
    return max(k, 1) if rows else 0


# 시맨틱 엣지 노이즈 컷오프 (도구 응답 단의 소비 임계값).
# 텍스트 매칭은 항상 1.0이므로 항상 통과. 시맨틱은 이 값 미만이면 응답에서 제외.
# (issue_linker 자체 생성 임계값은 0.30이라 기본값 0.5에서는 0.30~0.49 구간이 응답 단에서 마저 차단됨)
# eval 스윕용으로 env 외부화 — 시스템 프롬프트의 임계값 안내 문구도 이 값으로 렌더링된다.
_MIN_CONFIDENCE = float(os.environ.get("TOOLS_MIN_CONFIDENCE", "0.5"))

def _group_communications_by_thread(comms: list[dict]) -> list[dict]:
    """flat Communication 리스트를 conversation_id 기준으로 그룹핑한다.

    Slack 응답에서 LLM이 서로 다른 스레드를 한 대화로 합치거나 화자(author)를
    swap하지 않도록, 도구 응답 단계에서 스레드 경계를 명시적으로 구조화한다.

    입력: 각 dict는 최소 {body, conversation_id} 를 가지며 그 외 author, occurredAt,
          source, channel, confidence 등 임의 메타가 포함될 수 있다.

    반환:
        [
            {
                "conversation_id": str,
                "source":          str | None,  # SLACK / GITHUB
                "channel":         str | None,
                "messages": [
                    {author, body, occurredAt, confidence, ...},  # 그룹 메타 제외
                    ...
                ]  # occurredAt 오름차순 정렬
            },
            ...
        ]
        그룹들 자체는 첫 메시지 occurredAt 오름차순 정렬.

    예외 처리:
        - conversation_id가 None이거나 빈 문자열이면 별도 "(orphan)" 그룹으로 모음
        - 본문/저자/시각이 모두 None인 더미 dict는 제외 (OPTIONAL MATCH 미매치 잔재)
    """
    GROUP_KEYS = {"conversation_id", "source", "channel"}

    valid = [
        c for c in comms
        if any(c.get(k) is not None for k in c if k not in GROUP_KEYS)
    ]

    groups: dict[str, dict] = {}
    for c in valid:
        cid = c.get("conversation_id") or "(orphan)"
        if cid not in groups:
            groups[cid] = {
                "conversation_id": cid,
                "source":          c.get("source"),
                "channel":         c.get("channel"),
                "messages":        [],
            }
        msg = {k: v for k, v in c.items() if k not in GROUP_KEYS}
        groups[cid]["messages"].append(msg)

    for g in groups.values():
        g["messages"].sort(key=lambda m: m.get("occurredAt") or "")

    return sorted(
        groups.values(),
        key=lambda g: (g["messages"][0].get("occurredAt") or "") if g["messages"] else "",
    )
