"""tools.queries 공용 — 모든 쿼리 모듈이 공유하는 드라이버/상수/헬퍼."""

from graph.driver import get_driver


# TRIGGERED_BY 엣지 노이즈 컷오프.
# 텍스트 매칭은 항상 1.0이므로 항상 통과. 시맨틱은 0.5 미만이면 응답에서 제외.
# (issue_linker 자체 생성 임계값은 0.30이라 0.30~0.49 구간이 응답 단에서 마저 차단됨)
_MIN_CONFIDENCE = 0.5

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
