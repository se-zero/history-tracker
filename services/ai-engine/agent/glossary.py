"""내부 시스템 용어 → 사용자 표현 용어집 — 프롬프트와 서버 가드의 단일 출처.

도구 결과의 필드명이 답변 본문에 그대로 실려 사용자에게 노출되는 것을 막는다. 실측: "가장
논의가 많이 된 주제"에 "HT-129의 discussion_count가 12건"이라고 답했고, 사용자가 그 이름을
되묻자 무엇을 세는 값인지 추측으로 얼버무렸다. 사용자는 이 시스템의 내부 구조를 모르므로
필드명은 의미 없는 문자열이고, 내부 스키마를 그대로 드러내는 노출이기도 하다.

세 계층으로 나뉜다.

- METRIC_TERMS (Tier 1) — 사용자가 "그게 뭔데?"라고 되물을 수 있는 지표. 표기(label)와 정의
  (definition)를 함께 둔다. 정의는 프롬프트에 실려, 되물었을 때 추측 대신 이 문장으로 답하게 한다.
- FIELD_TERMS (Tier 2) — 표기만 사람 말로 바꾸면 되는 필드. 정의는 필요 없다.
- DETECT_ONLY (Tier 3) — 노드 라벨·관계 타입·도구 이름. **손으로 쓰지 않고 코드에서 파생한다**
  (스키마·도구가 바뀌면 자동으로 따라온다). 치환하지 않고 관측만 한다 — 사람이 표기를 정하지
  않은 어휘를 기계가 갈아끼우면 문장이 어색해지고, 문맥에 따라 정당한 언급일 수도 있다.
  관측 로그가 "실제로 무엇이 새는지" 알려주므로, 반복 등장하는 토큰을 Tier 1·2로 승격시킨다.

**치환 대상 선정 기준**: 모델이 그 개념을 부르려면 이 이름밖에 없는 필드만 넣는다. 값(이슈 키·
커밋 해시)은 원래 그대로 말해야 하므로 대상이 아니고, NEVER_REPLACE의 '일상어 한 단어'도
넣지 않는다 — message·source·title 같은 토큰은 사용자 문장에도 정상적으로 등장해서 치환하면
멀쩡한 문장이 깨진다. 그건 프롬프트 규칙으로만 다룬다.
"""

from tools.definitions import TOOLS
from tools.queries.explore import NODE_LABELS, REL_TYPES

# ─── Tier 1 — 표기 + 정의 ─────────────────────────────────────────────────────
# 정의는 사용자가 되물었을 때 그대로 답할 문장이다. 그래프 근거 없이 답해도 되는 유일한 진술이라
# (orchestrator의 [내부 용어 노출 금지] 절 참고), 실제 계산과 어긋나면 그대로 오답이 된다 —
# 쿼리를 바꾸면 이 문장도 함께 고친다.
METRIC_TERMS: dict[str, tuple[str, str]] = {
    "discussion_count": (
        "관련 대화 메시지 수",
        "슬랙·GitHub에서 이 이슈와 연결된 메시지 수입니다. 본문에 이슈 키가 적힌 명시적 연결, "
        "내용 유사도로 추정한 연결, 그리고 같은 스레드의 다른 메시지로 이어진 연결이 모두 "
        "포함됩니다. 스레드 하나의 메시지를 각각 세므로, 긴 대화 한 번이 이 수치를 크게 만들 수 "
        "있습니다 — 논의가 그만큼 여러 차례 있었다는 뜻은 아닙니다.",
    ),
    "duration_days": (
        "진행 기간(일)",
        "이슈가 생성된 시각부터 종료된 시각까지의 일수입니다. 종료된 이슈에만 있습니다.",
    ),
    "confidence": (
        "연결 신뢰도",
        "두 항목이 서로 관련 있다고 판단한 정도입니다. 본문에 명시된 참조는 1.0이고, "
        "내용 유사도로 추정한 연결은 그보다 낮습니다.",
    ),
    "link_source": (
        "연결 근거",
        "본문에 명시된 참조로 연결된 것인지, 내용 유사도로 추정해 연결한 것인지, "
        "같은 스레드의 다른 메시지에서 이어진 연결인지를 나타냅니다.",
    ),
    "status_category": (
        "진행 상태",
        "이슈 상태를 진행 전·진행 중·완료 세 가지로 묶은 구분입니다. "
        "트래커마다 다른 상태 이름을 같은 기준으로 비교하려고 씁니다.",
    ),
    "event_meaning": (
        "사건 종류",
        "그 시각이 무슨 사건의 시각인지를 나타냅니다 — 이슈 생성·수정·종료, 커밋 작성, "
        "PR 오픈·머지, 메시지 작성, 문서 생성·수정.",
    ),
    "relevance": (
        "관련도",
        "질문과 내용이 얼마나 비슷한지 나타내는 점수입니다.",
    ),
    "weighted_score": ("관련도", "질문과 내용이 얼마나 비슷한지 나타내는 점수입니다."),
    "weight": ("관련도", "질문과 내용이 얼마나 비슷한지 나타내는 점수입니다."),
    "commit_count": ("커밋 수", "해당 범위에서 집계된 커밋 개수입니다."),
    "total_commits": ("전체 커밋 수", "해당 범위에서 집계된 커밋 개수입니다."),
    "message_count": ("메시지 수", "해당 범위에서 집계된 대화 메시지 개수입니다."),
    "pr_count": ("PR 수", "해당 범위에서 집계된 PR 개수입니다."),
    "issue_created_count": ("생성한 이슈 수", "그 사람이 만든 이슈 개수입니다."),
    "issues_created": ("생성한 이슈", "그 사람이 만든 이슈입니다."),
    "issues_assigned": ("담당 이슈", "그 사람에게 할당된 이슈입니다."),
}

# ─── Tier 2 — 표기만 ──────────────────────────────────────────────────────────
FIELD_TERMS: dict[str, str] = {
    "occurredAt": "발생 시각",
    "createdAt": "생성 시각",
    "created_at": "생성 시각",
    "closedAt": "종료 시각",
    "closed_at": "종료 시각",
    "merged_at": "머지 시각",
    "diffSummary": "변경 요약",
    "diff_summary": "변경 요약",
    "conversation_id": "대화 스레드 ID",
    "external_id": "문서 ID",
    "issue_key": "이슈 키",
    "pr_number": "PR 번호",
    "node_type": "항목 종류",
    "ranked_by": "정렬 기준",
    "total_issues": "전체 이슈 수",
    "total_events": "전체 사건 수",
    "covered_from": "조회된 첫 사건 시각",
    "covered_to": "조회된 마지막 사건 시각",
    "_resolved_path": "실제 경로",
    "_resolved_via": "경로를 찾은 방식",
    "excerpt": "발췌",
    "candidates": "후보",
    "truncated": "일부 생략됨",
}

# 치환표 — Tier 1의 표기와 Tier 2를 합친다. 서버 가드(orchestrator._sanitize_internal_terms)와
# 프롬프트 용어집이 같은 출처를 봐야 지시와 실제 동작이 어긋나지 않는다.
REPLACEMENTS: dict[str, str] = {
    **{token: label for token, (label, _) in METRIC_TERMS.items()},
    **FIELD_TERMS,
}

# ─── Tier 3 — 검출 전용 (코드에서 파생) ───────────────────────────────────────
# 라벨 중 Issue·Actor·File·Document처럼 일상적인 영어 단어는 뺀다 — 관측 지표(eval의
# internal_term_leaks)에 오탐이 섞이면 신호가 죽는다. 대소문자를 구분해 매칭하므로 전부 대문자인
# 관계 타입과 CapWords 합성어는 사용자 문장에 우연히 등장할 여지가 거의 없다.
_GENERIC_LABELS = frozenset({"Issue", "Actor", "File", "Document"})
_TOOL_NAMES = frozenset(tool["function"]["name"] for tool in TOOLS)

DETECT_ONLY: frozenset[str] = frozenset(
    {*(NODE_LABELS - _GENERIC_LABELS), *REL_TYPES, *_TOOL_NAMES, "Cypher", "Neo4j"}
)

# ─── 절대 치환하지 않는 것 ────────────────────────────────────────────────────
# 도구 결과의 키이기도 하지만 일상어라 사용자 문장·코드에 정상적으로 등장한다. 치환하면 멀쩡한
# 문장이 깨지므로 프롬프트 규칙으로만 다룬다. 회귀 방지 테스트가 이 목록과 REPLACEMENTS·
# DETECT_ONLY가 겹치지 않는지 검사한다.
NEVER_REPLACE: frozenset[str] = frozenset({
    "message", "source", "title", "status", "body", "path", "text", "name", "id", "key",
    "count", "type", "data", "summary", "error", "label", "value", "context", "detail",
    "files", "events", "author", "channel", "url", "hash", "commit", "issues", "documents",
})


def prompt_glossary() -> str:
    """시스템 프롬프트에 실을 용어집 블록을 렌더한다.

    프롬프트가 이 함수에서 파생되므로 용어집을 늘려도 지시문이 자동으로 따라온다. Tier 1은
    정의까지 싣는다 — 사용자가 지표의 뜻을 되물었을 때 모델이 답할 문장이 여기밖에 없다.
    """
    lines = ["[지표·필드 용어집 — 왼쪽(내부 이름)은 오른쪽(사용자 표현)으로 옮겨 쓴다]"]
    for token, (label, definition) in METRIC_TERMS.items():
        lines.append(f"- {token} → {label}")
        lines.append(f"    뜻: {definition}")
    for token, label in FIELD_TERMS.items():
        lines.append(f"- {token} → {label}")
    return "\n".join(lines)
