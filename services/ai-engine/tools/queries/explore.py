"""범용 그래프 조회 — LLM이 작성한 Cypher를 검증·재작성해 읽기 전용으로 실행한다.

전용 도구가 없는 질문(속성 필터·집계·다중 조건 조인)을 위한 탈출구다(docs/graph-query-tool.md).
커버리지를 넓히는 대신 근거가 약해질 수 있으므로, 이 경로를 탄 답은 orchestrator가
answer_mode='exploratory'로 표시한다.

**격리는 협상 대상이 아니다.** LLM이 쓴 쿼리를 그대로 실행하지 않는다 — 모든 노드 패턴에
`{project_id: $project_id}`를 주입해 재작성한 뒤 실행한다. Neo4j community 에디션이라
프로젝트별 DB 분리가 불가능해서, 이 재작성이 격리의 유일한 보장이다.

검증은 **fail-closed**다. 파싱이 애매하면 통과시키지 않고 거부한다 — 거부는 LLM이 다시
쓰면 되지만, 잘못 통과시키면 다른 프로젝트 데이터가 샌다.

**왜 MATCH 절의 노드만 스코프하면 충분한가**: 엣지는 프로젝트를 건너뛰지 않는다. 모든
관계 MERGE가 양끝 노드를 project_id로 매칭하고(graph/writes.py), 시맨틱 빌더도 같은
project_id 안에서만 쌍을 만든다(docs/graph-schema.md). 따라서 시작 노드만 묶으면 거기서
뻗어나가는 탐색은 프로젝트를 벗어날 수 없다. 묶이지 않은 시작점을 만들 수 있는 경로
(MATCH 밖의 패턴·서브쿼리)는 전부 거부한다.
"""

import logging
import re

from neo4j import READ_ACCESS
from neo4j.exceptions import Neo4jError

from graph.driver import get_driver

logger = logging.getLogger(__name__)


# ─── 화이트리스트 ─────────────────────────────────────────────────────────────
# docs/graph-schema.md의 노드·관계 목록과 일치해야 한다. 스키마에 노드가 추가되면 여기도
# 함께 갱신한다.
#
# ActorAlias·ActorDecision은 **의도적으로 뺐다** — 동일인 판단·수동 병합의 내부 운영
# 노드라 질의로 열 이유가 없다. project_id는 갖고 있으므로 격리 문제는 아니고, 노출 범위
# 문제다.
#
# DocumentSection도 허용은 하되(부모 Document 조인·집계에 필요할 수 있어 완전히 막지는
# 않는다) SCHEMA_CARD에서 "본문 확인은 Document.body를 보라"고 안내한다 — text 속성은
# _STRIPPED_KEYS가 막는 embedding과 달리 정상적인 콘텐츠라 걸러지지 않는데, 섹션 하나가
# 1,500자에 달해 RETURN하면 그것만으로 컨텍스트를 잡아먹는다.

NODE_LABELS = frozenset({
    "ChangeSet", "PullRequest", "Issue", "Communication", "File", "Actor",
    "Document", "DocumentSection",
})

REL_TYPES = frozenset({
    "CREATED", "WROTE", "AUTHORED", "ASSIGNED_TO", "DISCUSSED_IN",
    "CHILD_OF", "TRIGGERED_BY", "CONTAINS", "MODIFIED", "REFERENCE",
    "EDITED", "PART_OF", "DESCRIBED_IN",
})

# 반환 행에서 지우는 속성 — 1536차원 임베딩이 컨텍스트를 통째로 잡아먹는다.
# (`RETURN c` 한 줄이면 8000자 상한을 혼자 넘긴다)
_STRIPPED_KEYS = frozenset({"embedding"})

_DEFAULT_LIMIT = 50    # LLM이 LIMIT을 안 쓰면 서버가 주입
_ROW_CAP = 200         # LIMIT을 크게 써도 이 값으로 깎는다
_MAX_HOPS = 5          # 가변 길이 관계 상한 (CHILD_OF*1..5 수준)
_TIMEOUT_SECONDS = 5.0

# 허용 절. 이 목록 밖의 절이 나오면 거부한다.
_CLAUSE_RE = re.compile(
    r"\b(OPTIONAL\s+MATCH|MATCH|WHERE|WITH|RETURN|ORDER\s+BY|SKIP|LIMIT)\b", re.IGNORECASE
)

# 쓰기·부작용·서브쿼리 — 하나라도 있으면 거부.
_FORBIDDEN_WORDS = (
    "CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE", "FOREACH",
    "CALL", "LOAD", "USE", "UNION", "UNWIND", "DROP", "ALTER",
    "GRANT", "REVOKE", "DENY", "SHOW", "TERMINATE", "PROFILE", "EXPLAIN",
)
_FORBIDDEN_RE = re.compile(r"\b(" + "|".join(_FORBIDDEN_WORDS) + r")\b", re.IGNORECASE)
# EXISTS{...} / COUNT{...} / COLLECT{...} 서브쿼리 — 스코프 주입이 미치지 않는 경로다.
# 집계 함수 count(...)는 괄호라 여기 걸리지 않는다.
_SUBQUERY_RE = re.compile(r"\b(EXISTS|COUNT|COLLECT)\s*\{", re.IGNORECASE)

# 노드 패턴 내부 형태: [변수] [:라벨...] [{속성맵}]  — 그 외 형태는 거부(인라인 WHERE 포함).
# 라벨은 선택으로 두고, 없을 때 허용할지는 호출부가 "이미 묶인 변수인가"로 판단한다.
_NODE_SHAPE_RE = re.compile(
    r"^\s*(?P<var>[A-Za-z_]\w*)?\s*(?P<labels>(?::\s*[A-Za-z_]\w*\s*)*)(?P<props>\{[\s\S]*\})?\s*$"
)
# WITH가 그대로 통과시키는 형태 — 평범한 변수 나열(`WITH i, c`)과 `WITH *`만 인정한다.
# 그 외(집계·별칭·DISTINCT 등)는 어떤 변수가 살아남는지 단정할 수 없으므로 묶임을 전부 버린다.
_PLAIN_WITH_RE = re.compile(r"^\s*[A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*\s*$")
# 관계 패턴 내부 형태: [변수] [:타입|타입...] [*범위] [{속성맵}]
_REL_SHAPE_RE = re.compile(
    r"^\s*(?P<var>[A-Za-z_]\w*)?\s*"
    r"(?::\s*(?P<types>[A-Za-z_]\w*(?:\s*\|\s*:?\s*[A-Za-z_]\w*)*)\s*)?"
    r"(?P<range>\*[\s\d.]*)?\s*"
    r"(?P<props>\{[\s\S]*\})?\s*$"
)
_LABEL_RE = re.compile(r":\s*([A-Za-z_]\w*)")
# MATCH 절의 패턴 바깥(top-level)에 나올 수 있는 문자 — 경로 변수(p =), 화살표, 구분자
_TOP_LEVEL_CHARS = set(" \t\r\n,-<>=") | set("abcdefghijklmnopqrstuvwxyz")
_TOP_LEVEL_CHARS |= set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")

_HINT = (
    "읽기 전용 단일 쿼리만 됩니다: MATCH / OPTIONAL MATCH / WHERE / WITH / RETURN / "
    "ORDER BY / SKIP / LIMIT. 모든 노드 패턴에는 라벨이 있어야 하고(예: (i:Issue)), "
    "project_id 조건은 서버가 자동으로 넣으므로 쓰지 마세요."
)


class GraphQueryError(ValueError):
    """검증 단계에서 거부된 쿼리 — 메시지는 LLM에게 그대로 전달해 교정을 유도한다."""


# ─── 1단계: 문자열·주석 마스킹 ────────────────────────────────────────────────


def _mask(cypher: str) -> str:
    """문자열 리터럴과 주석을 같은 길이의 공백으로 지운 사본을 만든다.

    구조 판별(키워드 탐색·괄호 매칭)은 전부 이 사본 위에서 한다 — 원문에서 하면
    문자열 안의 `MATCH`나 `{`가 구문으로 오인된다. 길이를 보존하므로 여기서 찾은
    위치를 원문에 그대로 적용할 수 있다.
    """
    out = list(cypher)
    i, n = 0, len(cypher)
    while i < n:
        ch = cypher[i]
        if ch in "'\"`":
            quote = ch
            out[i] = " "
            i += 1
            while i < n:
                if cypher[i] == "\\" and quote != "`":
                    out[i] = " "
                    if i + 1 < n:
                        out[i + 1] = " "
                    i += 2
                    continue
                closing = cypher[i] == quote
                out[i] = " "
                i += 1
                if closing:
                    break
            continue
        if ch == "/" and i + 1 < n and cypher[i + 1] == "/":
            while i < n and cypher[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if ch == "/" and i + 1 < n and cypher[i + 1] == "*":
            while i < n:
                closing = cypher[i] == "*" and i + 1 < n and cypher[i + 1] == "/"
                out[i] = " "
                i += 1
                if closing:
                    if i < n:
                        out[i] = " "
                    i += 1
                    break
            continue
        i += 1
    return "".join(out)


# ─── 2단계: 구문 형태 검증 ───────────────────────────────────────────────────


def _reject_forbidden(masked: str) -> None:
    if ";" in masked:
        raise GraphQueryError("여러 문장을 한 번에 실행할 수 없습니다 (';' 사용 불가).")
    found = _FORBIDDEN_RE.search(masked)
    if found:
        raise GraphQueryError(
            f"'{found.group(1).upper()}'는 쓸 수 없습니다 — 읽기 전용 조회만 허용됩니다."
        )
    if _SUBQUERY_RE.search(masked):
        raise GraphQueryError(
            "EXISTS{} / COUNT{} / COLLECT{} 서브쿼리는 쓸 수 없습니다. "
            "OPTIONAL MATCH와 IS NULL 조건으로 바꿔 쓰세요."
        )


def _clause_spans(masked: str) -> list[tuple[int, int, str]]:
    """절 키워드 위치 목록 — (start, end, 정규화된 키워드)."""
    spans = []
    for m in _CLAUSE_RE.finditer(masked):
        keyword = re.sub(r"\s+", " ", m.group(1).upper())
        spans.append((m.start(), m.end(), keyword))
    return spans


def _match_bracket(masked: str, open_at: int, limit: int) -> int:
    """여는 괄호 위치에서 짝을 찾는다. 모든 괄호 종류의 중첩을 함께 센다."""
    pairs = {"(": ")", "[": "]", "{": "}"}
    stack = [pairs[masked[open_at]]]
    i = open_at + 1
    while i < limit:
        ch = masked[i]
        if ch in pairs:
            stack.append(pairs[ch])
        elif ch in ")]}":
            if ch != stack[-1]:
                raise GraphQueryError("괄호 짝이 맞지 않습니다.")
            stack.pop()
            if not stack:
                return i
        i += 1
    raise GraphQueryError("괄호가 닫히지 않았습니다.")


def _validate_node(inner_masked: str, bound: set[str]) -> re.Match:
    """노드 패턴 형태·라벨을 검증한다. 라벨이 없으면 이미 묶인 변수여야 한다.

    `MATCH (i:Issue) OPTIONAL MATCH (c:ChangeSet)-[:TRIGGERED_BY]->(i)` 처럼 앞에서 묶은
    변수를 다시 참조하는 건 정상이고 이미 스코프돼 있다. 반면 묶이지 않은 라벨 없는 노드는
    그래프 전체(=모든 프로젝트) 스캔이라 거부한다.
    """
    m = _NODE_SHAPE_RE.match(inner_masked)
    if not m:
        raise GraphQueryError(
            f"노드 패턴 '({inner_masked.strip()})'을 해석할 수 없습니다. "
            "허용 형태는 (변수:라벨 {속성}) 뿐이며 패턴 안의 WHERE는 쓸 수 없습니다 "
            "— 조건은 패턴 밖 WHERE 절에 쓰세요."
        )
    labels = _LABEL_RE.findall(m.group("labels") or "")
    if not labels:
        var = m.group("var")
        if not var or var not in bound:
            raise GraphQueryError(
                f"노드 패턴 '({inner_masked.strip()})'에 라벨이 없습니다. "
                f"앞에서 묶은 변수를 다시 쓰는 게 아니면 라벨을 붙이세요 "
                f"(사용 가능: {', '.join(sorted(NODE_LABELS))})."
            )
        return m
    unknown = [lb for lb in labels if lb not in NODE_LABELS]
    if unknown:
        raise GraphQueryError(
            f"조회할 수 없는 라벨입니다: {', '.join(unknown)}. "
            f"사용 가능: {', '.join(sorted(NODE_LABELS))}"
        )
    return m


def _validate_rel(inner_masked: str) -> None:
    m = _REL_SHAPE_RE.match(inner_masked)
    if not m:
        raise GraphQueryError(
            f"관계 패턴 '[{inner_masked.strip()}]'을 해석할 수 없습니다. "
            "허용 형태는 [변수:타입*범위 {속성}] 뿐입니다."
        )
    types = m.group("types")
    if types:
        for rel in re.split(r"\s*\|\s*:?\s*", types.strip()):
            if rel not in REL_TYPES:
                raise GraphQueryError(
                    f"조회할 수 없는 관계 타입입니다: {rel}. "
                    f"사용 가능: {', '.join(sorted(REL_TYPES))}"
                )
    hops = m.group("range")
    if hops:
        bounds = re.findall(r"\d+", hops)
        # `*`(무제한)·`*2..`(상한 없음)는 그래프 전체를 훑는다 — 상한을 요구한다.
        if ".." in hops:
            if len(bounds) < 2:
                raise GraphQueryError(f"가변 길이 관계에는 상한이 필요합니다 (최대 {_MAX_HOPS}).")
            upper = int(bounds[1])
        elif len(bounds) == 1:
            upper = int(bounds[0])
        else:
            raise GraphQueryError(f"가변 길이 관계에는 상한이 필요합니다 (최대 {_MAX_HOPS}).")
        if upper > _MAX_HOPS:
            raise GraphQueryError(f"가변 길이 관계는 최대 {_MAX_HOPS}홉까지만 가능합니다.")


# ─── 3단계: project_id 주입 재작성 ───────────────────────────────────────────


def _scan_match_segment(masked: str, start: int, end: int, bound: set[str]) -> list[tuple[int, int]]:
    """MATCH 절 구간을 훑어 노드 패턴을 검증하고, 주입 위치를 반환한다.

    반환: (삽입 지점, 종류) — 종류 0이면 속성맵 뒤(`{` 다음), 1이면 닫는 괄호 앞.
    `bound`는 이 구간에서 새로 묶인 변수만큼 갱신된다(호출부와 공유).
    """
    injections: list[tuple[int, int]] = []
    i = start
    while i < end:
        ch = masked[i]
        if ch == "(":
            close = _match_bracket(masked, i, end)
            inner = masked[i + 1:close]
            node = _validate_node(inner, bound)
            var = node.group("var")
            if not _LABEL_RE.findall(node.group("labels") or ""):
                # 이미 묶인 변수의 재참조 — 그 변수가 묶일 때 이미 스코프됐다.
                i = close + 1
                continue
            if var:
                bound.add(var)
            if node.group("props"):
                brace = masked.index("{", i + 1, close)
                injections.append((brace + 1, 0))
            else:
                injections.append((close, 1))
            i = close + 1
            continue
        if ch == "[":
            close = _match_bracket(masked, i, end)
            _validate_rel(masked[i + 1:close])
            i = close + 1
            continue
        if ch not in _TOP_LEVEL_CHARS:
            raise GraphQueryError(
                f"MATCH 절에서 해석할 수 없는 문자 '{ch}'를 만났습니다. "
                "조건은 패턴이 아니라 WHERE 절에 쓰세요."
            )
        i += 1
    return injections


# MATCH 절 **밖**의 그래프 패턴 — WHERE 패턴 술어와 RETURN 패턴 컴프리헨션
# (`RETURN [(a:Issue)-->(b:File) | b.path]`)이 여기 해당한다. 이것들은 MATCH 절이 아니라서
# project_id 주입을 타지 않고, 시작 노드가 묶이지 않으면 프로젝트 전체를 훑는다.
# 반환값이 스칼라면 후검증(_belongs_to_project)도 못 잡으므로 아예 거부한다 — 같은 일을
# OPTIONAL MATCH로 쓰면 정상적으로 스코프된다.
_PATTERN_TOKEN_RE = re.compile(r"-\[|->|<-|--")


def _reject_patterns_outside_match(masked: str, clauses: list[tuple[int, int, str]]) -> None:
    for index, (start, end, keyword) in enumerate(clauses):
        if keyword in ("MATCH", "OPTIONAL MATCH"):
            continue
        segment_end = clauses[index + 1][0] if index + 1 < len(clauses) else len(masked)
        if _PATTERN_TOKEN_RE.search(masked[end:segment_end]):
            raise GraphQueryError(
                f"{keyword} 절에는 그래프 패턴을 쓸 수 없습니다 "
                "(WHERE 패턴 술어·패턴 컴프리헨션 포함). OPTIONAL MATCH로 분리해 쓰세요."
            )


def _limit_rewrites(masked: str, clauses: list[tuple[int, int, str]]) -> tuple[list, bool]:
    """LIMIT 값을 상한으로 깎는 재작성 목록과, LIMIT 존재 여부를 반환한다."""
    rewrites = []
    seen = False
    for start, end, keyword in clauses:
        if keyword != "LIMIT":
            continue
        seen = True
        m = re.match(r"\s*(\d+)", masked[end:])
        if not m:
            raise GraphQueryError("LIMIT에는 정수만 쓸 수 있습니다 (예: LIMIT 20).")
        if int(m.group(1)) > _ROW_CAP:
            value_start = end + m.start(1)
            rewrites.append((value_start, value_start + len(m.group(1)), str(_ROW_CAP)))
    return rewrites, seen


def compile_query(cypher: str) -> str:
    """LLM이 쓴 Cypher를 검증하고 project_id를 주입한 실행 가능 쿼리로 바꾼다.

    거부 사유는 GraphQueryError로 던지며, 그 메시지는 LLM에게 그대로 전달된다.
    """
    if not cypher or not cypher.strip():
        raise GraphQueryError("cypher가 비어 있습니다.")

    masked = _mask(cypher)
    _reject_forbidden(masked)

    clauses = _clause_spans(masked)
    if not clauses or clauses[0][2] != "MATCH":
        raise GraphQueryError("쿼리는 MATCH로 시작해야 합니다.")
    if masked[:clauses[0][0]].strip():
        raise GraphQueryError("MATCH 앞에 다른 구문을 쓸 수 없습니다.")
    if not any(keyword == "RETURN" for _, _, keyword in clauses):
        raise GraphQueryError("RETURN 절이 필요합니다.")
    _reject_patterns_outside_match(masked, clauses)

    rewrites: list[tuple[int, int, str]] = []
    bound: set[str] = set()
    for index, (start, end, keyword) in enumerate(clauses):
        segment_end = clauses[index + 1][0] if index + 1 < len(clauses) else len(masked)
        if keyword == "WITH":
            # WITH는 변수를 떨어뜨린다. 살아남는 게 확실한 형태(`WITH *`, 평범한 나열)가
            # 아니면 묶임을 전부 버린다 — 그래야 `WITH count(x) AS n MATCH (x)` 처럼
            # 같은 이름이 **새 미묶임 변수**로 되살아나 전체 스캔이 되는 걸 막는다.
            projection = masked[end:segment_end].strip()
            if projection == "*":
                continue
            if _PLAIN_WITH_RE.match(projection):
                bound &= {name.strip() for name in projection.split(",")}
            else:
                bound.clear()
            continue
        if keyword not in ("MATCH", "OPTIONAL MATCH"):
            continue
        for position, kind in _scan_match_segment(masked, end, segment_end, bound):
            text = "project_id: $project_id, " if kind == 0 else " {project_id: $project_id}"
            rewrites.append((position, position, text))

    if not rewrites:
        raise GraphQueryError("조회할 노드 패턴이 없습니다 — MATCH에 (변수:라벨) 형태를 쓰세요.")

    limit_rewrites, has_limit = _limit_rewrites(masked, clauses)
    rewrites.extend(limit_rewrites)

    compiled = cypher
    for start, end, text in sorted(rewrites, key=lambda r: r[0], reverse=True):
        compiled = compiled[:start] + text + compiled[end:]
    if not has_limit:
        compiled = f"{compiled.rstrip()}\nLIMIT {_DEFAULT_LIMIT}"
    return compiled


# ─── 4단계: 실행 + 후검증 ────────────────────────────────────────────────────


def _sanitize(value):
    """반환 값에서 임베딩을 제거한다 (노드 투영에 딸려오는 1536차원 배열)."""
    if isinstance(value, dict):
        return {k: _sanitize(v) for k, v in value.items() if k not in _STRIPPED_KEYS}
    if isinstance(value, list):
        return [_sanitize(v) for v in value]
    return value


def _belongs_to_project(value, project_id: str) -> bool:
    """반환 행에 다른 프로젝트 노드가 섞이지 않았는지 확인한다 (재작성 실패 대비 2중 방어)."""
    if isinstance(value, dict):
        if "project_id" in value and value["project_id"] != project_id:
            return False
        return all(_belongs_to_project(v, project_id) for v in value.values())
    if isinstance(value, list):
        return all(_belongs_to_project(v, project_id) for v in value)
    return True


async def run_graph_query(project_id: str, cypher: str) -> list | dict:
    """LLM이 작성한 Cypher를 검증·재작성해 읽기 전용으로 실행한다.

    반환은 행 리스트다 — dict로 감싸면 executor의 행 단위 잘림을 못 타고 문자열 컷으로
    떨어져 JSON이 깨진다. 검증·실행 실패만 {"error": ...} dict로 돌려 LLM이 교정하게 한다.
    """
    try:
        compiled = compile_query(cypher)
    except GraphQueryError as exc:
        return {"error": str(exc), "hint": _HINT}

    logger.info("run_graph_query project=%s query=%r", project_id, compiled)
    try:
        async with get_driver().session(default_access_mode=READ_ACCESS) as session:
            async with await session.begin_transaction(timeout=_TIMEOUT_SECONDS) as tx:
                result = await tx.run(compiled, project_id=project_id)
                rows = await result.data()
    except Neo4jError as exc:
        # 구문·타입 오류는 LLM이 고칠 수 있으므로 사유를 전달한다 (쿼리는 LLM이 쓴 것이라
        # 내부 정보 노출이 아니다). 접속 정보 등이 섞이지 않도록 길이를 자른다.
        message = (getattr(exc, "message", None) or str(exc))[:300]
        return {"error": f"Cypher 실행 실패: {message}", "hint": _HINT}

    rows = [row for row in rows if _belongs_to_project(row, project_id)]
    rows = [_sanitize(row) for row in rows[:_ROW_CAP]]
    if not rows:
        return [{"message": "조회 결과가 없습니다. 조건을 완화하거나 다른 라벨·속성으로 다시 시도하세요."}]
    return rows


# ─── describe_graph — 속성 값 분포 ───────────────────────────────────────────
# 라벨·속성·관계 골격은 프롬프트의 정적 스키마 카드가 담당한다. 여기서는 프로젝트마다
# 달라지는 것(status·channel 등 실제 값)만 조회한다.

_DESCRIBE_ENUMS: dict[str, tuple[str, ...]] = {
    "Issue":         ("status", "status_category", "issue_type", "priority", "source"),
    "PullRequest":   ("state", "base_branch", "source"),
    "Communication": ("source", "channel"),
    "ChangeSet":     ("source",),
    "File":          (),
    "Actor":         (),
}

_ENUM_VALUE_LIMIT = 20

# 시스템 프롬프트에 주입하는 정적 스키마 카드. 라벨·속성·관계는 코드가 정하는 값이라
# 프로젝트마다 다르지 않으므로 여기 상수로 둔다 — 화이트리스트 바로 옆에 두어 함께
# 갱신되게 한다. 값 분포(status·channel 등 실제 값)는 프로젝트마다 다르므로
# describe_graph 도구로 조회한다.
#
# 속성명은 graph/writes.py의 SET 절이 진실이다. embedding은 의도적으로 빼둔다
# (1536차원 배열이라 RETURN 대상이 되면 안 된다).
SCHEMA_CARD = """\
[그래프 스키마 — run_graph_query용]
노드 (전부 project_id를 갖지만 쿼리에 쓰지 말 것 — 서버가 주입한다)
- ChangeSet(커밋): hash, message, occurredAt(=커밋 시각), source
- PullRequest: pr_number, title, body, state, base_branch, url, createdAt(=오픈), occurredAt(=머지), issue_keys
- Issue: external_id(불변 ID, source와 함께 유니크 키), source, issue_key(사람용 표시 키, nullable), status(원문, nullable), status_category(open|in_progress|closed — 종료 판정은 이 축), title, body, issue_type, priority, createdAt, closedAt, occurredAt(=최종 수정) — GitHub 이슈는 source='GITHUB', issue_key='#N'
  ※ source='__stub__'는 텍스트 참조만 있고 아직 수집되지 않은 이슈의 센티널 노드 — title/body 없음, 실이벤트 도착 시 흡수된다
- Communication(Slack 등 대화 메시지): body, channel, url, conversation_id, createdAt, occurredAt, source, llm_filtered
- File: path
- Actor(사람): uuid, name, aliases
- Document(문서, 예: Notion 페이지): external_id(불변 ID, source와 함께 유니크 키), source, title, body(평문 전체 — 길다, 필요하면 get_document_context를 우선 고려), url, createdAt, occurredAt(=최종 수정), parent_type, parent_external_id
- DocumentSection(문서 내부 섹션 — 검색 단위): heading_path, text(섹션 본문 — 이것도 길다. **본문 확인은 여기가 아니라 Document.body나 get_document_context를 쓴다**), document_external_id, ordinal

관계 (방향 주의)
- (Actor)-[:AUTHORED]->(ChangeSet | PullRequest)
- (Actor)-[:CREATED]->(Issue)
- (Actor)-[:WROTE]->(Communication | Document)
- (Actor)-[:EDITED]->(Document)              누적 관계(최종 편집자뿐 아니라 과거 편집자도 남음)
- (Issue)-[:ASSIGNED_TO]->(Actor)
- (Issue)-[:CHILD_OF]->(Issue)            부모 방향. 하위 작업 조회는 역방향으로
- (Document)-[:CHILD_OF]->(Document)      부모 페이지 방향
- (DocumentSection)-[:PART_OF]->(Document)
- (Issue)-[:DISCUSSED_IN]->(Communication)  confidence(시맨틱 엣지만)
- (Document)-[:DISCUSSED_IN]->(Communication) source('text'만 — 이 방향은 semantic 변형이 없다)
- (Issue)-[:DESCRIBED_IN]->(Document)      source('text'|'semantic'), confidence, section(semantic만 — 근거 섹션의 heading_path)
- (ChangeSet)-[:TRIGGERED_BY]->(Issue)      source('text'|'semantic'), confidence
- (ChangeSet)-[:MODIFIED]->(File)           diffSummary
- (ChangeSet)-[:REFERENCE]->(Communication | Document) source('text'|'semantic'), confidence, section(Document 대상 semantic만)
- (PullRequest)-[:CONTAINS]->(ChangeSet)

주의
- 모든 시각은 Neo4j datetime이다. 문자열 비교가 필요하면 toString()을 쓴다.
- source='semantic'인 엣지의 confidence는 유사도 기반 추정이다. source='text'는 명시 참조이며
  confidence=1.0이다. 직접 조회에서 추론만 고르려면 WHERE r.source = 'semantic' AND r.confidence >= 0.5를 쓴다.
- 문서 하나·문서 탐색은 이 도구보다 get_document_context/search_documents를 우선 쓴다 — 본문이
  잘리지 않고, DESCRIBED_IN/REFERENCE의 text·semantic 구분도 이미 정리해서 준다.
"""


async def describe_graph(project_id: str, label: str) -> dict:
    """라벨 하나의 노드 수·보유 속성·주요 속성의 실제 값 분포를 반환한다."""
    if label not in NODE_LABELS:
        return {
            "error": f"조회할 수 없는 라벨입니다: {label}",
            "hint": f"사용 가능: {', '.join(sorted(NODE_LABELS))}",
        }

    async with get_driver().session(default_access_mode=READ_ACCESS) as session:
        count_result = await session.run(
            f"MATCH (n:{label} {{project_id: $project_id}}) RETURN count(n) AS total",
            project_id=project_id,
        )
        total = (await count_result.single())["total"]

        keys_result = await session.run(
            f"""
            MATCH (n:{label} {{project_id: $project_id}})
            UNWIND keys(n) AS key
            RETURN DISTINCT key ORDER BY key
            """,
            project_id=project_id,
        )
        keys = [row["key"] for row in await keys_result.data() if row["key"] not in _STRIPPED_KEYS]

        values: dict[str, list[dict]] = {}
        for prop in _DESCRIBE_ENUMS[label]:
            if prop not in keys:
                continue
            value_result = await session.run(
                f"""
                MATCH (n:{label} {{project_id: $project_id}})
                WHERE n.{prop} IS NOT NULL
                RETURN n.{prop} AS value, count(*) AS count
                ORDER BY count DESC LIMIT {_ENUM_VALUE_LIMIT}
                """,
                project_id=project_id,
            )
            values[prop] = await value_result.data()

    return {"label": label, "total": total, "properties": keys, "value_distribution": values}
