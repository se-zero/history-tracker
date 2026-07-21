"""tools.queries 공용 — 모든 쿼리 모듈이 공유하는 드라이버/상수/헬퍼."""

import json
import os
from collections.abc import Callable
from datetime import datetime, timezone

from graph.driver import get_driver


# ─── 시간축: 노드 → 이벤트 펼치기 ──────────────────────────────────────────────
# 한 노드는 시간 이벤트를 여러 개 낳는다 — Issue는 생성·종료, PR은 오픈·머지.
# 이 표가 단일 출처다 (docs/timeline-scope.md의 매핑 표, agent/orchestrator.py의
# 타임스탬프 의미 사전과 일치해야 한다).
#
# Issue.occurredAt(최종 업데이트)은 생성도 종료도 아니라 의미가 모호하므로 이벤트로
# 만들지 않는다 — 라벨 없이 노출하면 모델이 생성/완료로 추정해 뒤집는다
# (docs/query-quality-issues.md 문제 2·3).

EVENT_SPECS: dict[str, tuple[tuple[str, str], ...]] = {
    "Issue":         (("createdAt", "issue_created"), ("closedAt", "issue_closed")),
    "PullRequest":   (("createdAt", "pr_opened"),     ("occurredAt", "pr_merged")),
    "ChangeSet":     (("occurredAt", "commit_authored"),),
    "Communication": (("occurredAt", "message_posted"),),
}


def _event_time(value) -> str | None:
    """이벤트 시각을 **UTC ISO 문자열(밀리초 고정)**로 정규화. 값이 없으면 None.

    Cypher `toString(n.x)`가 미매치(OPTIONAL MATCH)에서 null을 주고, 드라이버가 넘긴
    neo4j DateTime이 그대로 오는 경우도 있어 양쪽을 함께 흡수한다. 문자열 "None"은
    파이썬 str(None)이 섞여 들어온 값이라 시각으로 취급하지 않는다.

    **정규화가 필요한 이유**: 그래프의 시각 표기가 섞여 있다 — `Issue.createdAt`은
    `+09:00` 오프셋으로, 나머지(ChangeSet/PullRequest/Communication)는 `Z`로 저장된다.
    타임라인은 ISO 문자열 사전순으로 정렬하는데, 사전순 = 시간순은 **표기가 동일할
    때만** 성립한다. 오프셋이 섞이면 `T19:47+09:00`(=10:47Z)이 `T11:07Z`보다 뒤로
    가서 순서가 뒤집힌다 — 시간축 도구의 존재 이유가 순서 정확성이므로 여기서 막는다.
    표기를 하나로 통일해야 하므로 이미 Z인 값도 같은 경로로 다시 찍는다.
    또한 시스템 프롬프트가 "모든 시각은 UTC"라고 선언하므로 표시값도 UTC여야 한다.
    """
    if value is None:
        return None
    text = value if isinstance(value, str) else str(value)
    text = text.strip()
    if not text or text == "None":
        return None
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return text  # 파싱 불가 — 원문을 그대로 두어 정보를 잃지 않는다
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)  # naive는 UTC로 간주
    return parsed.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def expand_events(node_type: str, timestamps: dict, data: dict) -> list[dict]:
    """한 노드를 EVENT_SPECS에 따라 이벤트 목록으로 펼친다.

    timestamps: 시각 속성 dict (예: {"createdAt": ..., "closedAt": ...}).
                해당 키가 없거나 값이 비면 그 이벤트는 만들지 않는다.
    data:       이벤트에 실을 본문 dict — 같은 노드에서 나온 이벤트들이 공유한다.

    반환 항목 구조: {type, event_meaning, occurredAt, data}
    """
    events: list[dict] = []
    for prop, meaning in EVENT_SPECS.get(node_type, ()):
        at = _event_time(timestamps.get(prop))
        if at is None:
            continue
        events.append({
            "type": node_type,
            "event_meaning": meaning,
            "occurredAt": at,
            "data": data,
        })
    return events


def normalize_time(value) -> str | None:
    """외부에서 받은 시각(도구 인자의 from_time/to_time 등)을 이벤트 시각과 같은 표기로 맞춘다."""
    return _event_time(value)


def sort_events(events: list[dict]) -> list[dict]:
    """시각 없는 이벤트를 버리고 occurredAt 오름차순으로 정렬한다.

    occurredAt은 ISO-8601 문자열이라 사전순 = 시간순이다.
    """
    valid = [e for e in events if _event_time(e.get("occurredAt")) is not None]
    return sorted(valid, key=lambda e: e["occurredAt"])


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
# (issue_linker 자체 생성 임계값은 0.34라 기본값 0.5에서는 0.34~0.49 구간이 응답 단에서 마저 차단됨)
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
