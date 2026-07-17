import json
import logging
import os

from openai_client import Priority, chat_completion
from tools.definitions import TOOLS
from tools.executor import execute
from tools.queries._common import _MIN_CONFIDENCE

logger = logging.getLogger(__name__)

_MODEL = os.environ.get("QUERY_MODEL", "gpt-5.4-mini")
# reasoning 모델 전용 노브 (minimal/low/medium/high). 빈 값이면 API 기본값을 따른다.
# eval 스윕용 — 코드 수정 없이 env로 주입한다 (docs/measurement.md의 임계값 주입과 같은 방식).
_REASONING_EFFORT = os.environ.get("QUERY_REASONING_EFFORT", "")
_MAX_ITERATIONS = 10


def _model_kwargs() -> dict:
    """chat_completion에 넘길 모델 공통 kwargs — reasoning_effort는 설정된 경우에만 포함."""
    kwargs = {"model": _MODEL}
    if _REASONING_EFFORT:
        kwargs["reasoning_effort"] = _REASONING_EFFORT
    return kwargs

_RUNNING_SUMMARY_SCHEMA = {
    "name": "running_summary",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "summary": {"type": "string"},
            "entities": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "type": {"type": "string"},
                        "id": {"type": "string"},
                    },
                    "required": ["type", "id"],
                    "additionalProperties": False,
                },
            },
            "unresolved_aspects": {
                "type": "array",
                "items": {"type": "string"},
            },
        },
        "required": ["summary", "entities", "unresolved_aspects"],
        "additionalProperties": False,
    },
}


# ─── Structured Output schema — LLM 환각 방지를 위한 답변 강제 형식 ─────────────
#
# OpenAI Structured Outputs로 LLM이 출력 구조를 벗어나지 못하게 강제한다.
# 사실 진술은 evidence[]에 출처 ID·인용·시각·이벤트 의미와 함께 묶여야 하고,
# 그래프에서 확인할 수 없는 측면은 unknown_aspects[]에 명시한다.
# 자유 텍스트로 종결문·일반론·추정을 끼워 넣을 수 없는 구조가 핵심 가드.

_EVIDENCE_TYPES = ["commit", "pull_request", "issue", "message"]

_EVENT_MEANINGS = [
    "issue_created", "issue_updated", "issue_closed",
    "commit_authored",
    "pr_opened", "pr_merged",
    "message_posted",
]

_GROUNDED_ANSWER_SCHEMA = {
    "name": "grounded_answer",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "summary": {
                "type": "string",
                "description": (
                    "사용자 질문에 대한 1~3문장 요약. "
                    "evidence[]에 등재된 사실만 기반으로 작성. "
                    "종결문(예: '추가 궁금한 점이 있으면…'), 일반론, 그래프에 없는 추정·요약 금지."
                ),
            },
            "evidence": {
                "type": "array",
                "description": (
                    "summary가 의존하는 그래프 근거 목록. 각 항목은 그래프의 단일 노드/엣지 출처를 가리킨다."
                ),
                "items": {
                    "type": "object",
                    "properties": {
                        "type": {
                            "type": "string",
                            "enum": _EVIDENCE_TYPES,
                            "description": "근거 노드 타입.",
                        },
                        "id": {
                            "type": "string",
                            "description": (
                                "1차 식별자. commit→hash 앞 7자, pull_request→'#번호', "
                                "issue→jira_key, message→conversation_id."
                            ),
                        },
                        "occurredAt": {
                            "type": "string",
                            "description": "ISO-8601 UTC 시각. 그래프의 occurredAt/createdAt/closedAt 중 event_meaning에 맞는 값.",
                        },
                        "event_meaning": {
                            "type": "string",
                            "enum": _EVENT_MEANINGS,
                            "description": (
                                "occurredAt이 가리키는 사건 종류. "
                                "타임스탬프 의미 사전을 참고해 정확히 분류 (예: Issue 종료시각이면 issue_closed)."
                            ),
                        },
                        "author": {
                            "type": ["string", "null"],
                            "description": "해당 항목의 작성자/생성자 이름. 없으면 null.",
                        },
                        "quote": {
                            "type": "string",
                            "description": (
                                "도구 결과에서 직접 가져온 텍스트(commit message / pr title / issue title+body / "
                                "message body). 요약·번역·재구성 금지. 길면 앞부분만 인용."
                            ),
                        },
                    },
                    "required": ["type", "id", "occurredAt", "event_meaning", "author", "quote"],
                    "additionalProperties": False,
                },
            },
            "unknown_aspects": {
                "type": "array",
                "description": (
                    "사용자 질문 중 그래프에서 근거를 찾지 못한 측면들. "
                    "예: '이슈 도입 배경은 본문에 명시되어 있지 않음'. "
                    "없으면 빈 배열."
                ),
                "items": {"type": "string"},
            },
        },
        "required": ["summary", "evidence", "unknown_aspects"],
        "additionalProperties": False,
    },
}

_SYSTEM_PROMPT = """\
당신은 코드 변경 맥락 분석 AI입니다.
GitHub(커밋, PR), Jira(이슈), Slack(메시지) 데이터가 Neo4j 지식 그래프로 연결되어 있습니다.
제공된 도구를 사용해 그래프를 탐색하고 사용자의 질문에 답하세요.

[답변 규칙]
- 도구 결과에 없는 내용은 절대 추측하거나 지어내지 마세요. 그래프에 근거가 없는 측면은
  unknown_aspects[]에 명시하고, summary에 일반론·추정으로 채우지 마세요.
- 여러 출처(Jira, Slack, PR)가 서로 다른 이유를 설명하면 각 관점을 구분해 제시하세요.
- 연결 confidence가 __MIN_CONF__~0.7 구간인 항목을 인용할 때는 summary에서 "유사도 기반 추정" 등으로 명시하세요.
  __MIN_CONF__ 미만 엣지는 쿼리 단에서 이미 차단되어 도구 결과에 없습니다.
- summary, unknown_aspects, evidence[*].quote 모두 한국어로 작성하세요 (단, 원문이 영어/코드면 그대로 인용).

[그래프 타임스탬프 의미 사전 — 필드명을 추정으로 해석하지 말 것]
- Issue.occurredAt   = 이슈 최종 업데이트 시각  (event_meaning = issue_updated)
- Issue.createdAt    = 이슈 생성 시각          (event_meaning = issue_created)
- Issue.closedAt     = 이슈 종료 시각          (event_meaning = issue_closed; status가 완료/Done/Closed/Resolved일 때만 존재)
- ChangeSet.occurredAt    = commit 시각        (event_meaning = commit_authored)
- PullRequest.occurredAt  = 머지 시각          (event_meaning = pr_merged; open PR은 null)
- PullRequest.createdAt   = PR 생성 시각       (event_meaning = pr_opened)
- Communication.occurredAt = 메시지 작성 시각  (event_meaning = message_posted)
모든 시각은 UTC. 사용자가 시간대 명시를 요구하지 않는 한 그대로 인용하세요.
get_timeline 결과의 각 이벤트는 event_meaning 필드를 직접 제공하므로 그것을 사용하세요.

[Slack/Communication 인용 규칙]
- 도구 결과의 discussions / communications / comm_contexts 필드는 conversation_id별로
  그룹핑된 구조이다: [{conversation_id, source, channel, messages:[...]}, ...].
- 서로 다른 conversation_id에 속한 메시지를 같은 대화로 합치지 마세요.
- 화자(author)와 메시지 본문(body)은 한 메시지 객체 안에서 1:1로 짝지어져 있다.
  그룹/메시지 간 author를 swap해서 인용하지 마세요.
- 메시지를 인용할 때 가능하면 conversation_id를 함께 표기해 어느 스레드인지 명확히 하세요.
- 한 스레드의 대표 메시지만 보이고 전체 흐름이 필요하면 그 conversation_id로
  get_thread_context를 호출해 전체 메시지 시퀀스를 조회하세요.

[증거 인용 규칙 — grounded_answer Structured Output에 그대로 반영됨]
- summary에 적힌 사실은 evidence[]에 매핑되어야 합니다. evidence 없는 합성 문장 금지.
- 마무리/종결/요약 문장(예: "추가 궁금한 점이 있으면…", "이 정보를 통해…", "모든 작업이 완료되어 …")
  을 summary에 추가하지 마세요. summary가 곧 답변의 시작이자 끝입니다.
- evidence[*].quote는 도구 결과 텍스트(commit message / pr title+body / issue title+body / message body)
  에서 요약·번역·재구성 없이 직접 인용하세요. 너무 길면 앞부분만 잘라 인용.
- evidence[*].id 형식 (반드시 준수):
    commit       → hash 앞 7자
    pull_request → "#번호" (예: "#18")
    issue        → jira_key (예: "HT-37")
    message      → conversation_id (Slack ts)
- evidence[*].event_meaning은 위 [타임스탬프 의미 사전]의 enum 값만 사용.
- "왜", "배경", "이유" 류 질문에 명확한 근거(이슈 본문 / 슬랙 메시지)가 없으면
  unknown_aspects에 명시하고, summary에서 일반론으로 채우지 마세요.
- 근거가 이유·배경을 직접 명시하지 않고 시사만 한다면, 단정형("~때문이다", "~하기 위해서였다")
  으로 쓰지 말고 추론임을 드러내세요 — 어떤 근거에서의 추론인지 밝히고 "~로 보인다" 수준으로
  서술하며, 확정 근거가 없다는 점은 unknown_aspects에 명시하세요. 추론 자체는 가치 있는
  답변입니다. 금지되는 것은 근거 없는 창작과 근거 이상의 확신입니다.

[모호한 질문 처리 절차]
질문에 구체적 entity(jira_key / commit hash / PR # / 파일 경로)가 없으면 다음을 순서대로 시도:
  1) 질문의 명사구·기능 이름을 키워드로 search_by_keyword 호출
     (예: "초기 데이터 수집 파이프라인 구조" → "데이터 수집 파이프라인")
  2) search_by_keyword 결과가 약하면(1건 이하 또는 score 낮음) get_recent_activity로 시간 기반 탐색
     - "프로젝트 초기" → 가장 오래된 기간(전체 그래프 첫 30일 등)
     - "최근" / "이번 주" → 현재 기준 최근 7~30일
  3) 위 두 단계가 모두 비면 unknown_aspects에 "그래프에서 관련 항목을 찾지 못함" 명시.
즉시 "확인되지 않음"으로 종료 금지 — 최소 한 번은 도구를 호출해 탐색하세요.

[파일 경로 모호 처리]
- get_file_history 결과에 'candidates' 필드가 있으면 그 중 가장 적절한 경로로 재호출하세요.
- 결과에 '_resolved_via' = 'basename_match' 또는 'stem_match'이 있으면, evidence 또는 summary의
  파일 경로 인용에 LLM이 추정한 path가 아니라 '_resolved_path' 값을 사용하세요.
- 파일명 확장자를 모르면 확장자 없이 호출해도 됨 (자동 stem 매칭).

[2계층 결과 처리 — get_file_history · get_actor_activity]
- 이 도구들의 결과는 {detail:[...], context:[...]} 2계층이다. detail은 본문 포함(인용 대상),
  context는 나머지 항목의 시간순 개요 stub(본문 없음)이다.
- 근거 인용(evidence)은 detail 항목에서 하세요. context는 전체 흐름을 파악하고 어떤 항목을 더
  볼지 고르는 용도입니다.
- context의 특정 항목이 질문에 관련돼 인용이 필요하면, 그 항목의 상세 도구로 본문을 조회한 뒤
  인용하세요 — commit은 hash로 get_changeset_context, message는 conversation_id로
  get_thread_context, pull_request는 pr_number로 get_pr_context. stub의 제목만으로 quote를
  지어내지 마세요.
- context에 있다는 이유만으로 다수 항목을 무더기로 인용하지 마세요. 질문에 답하는 데 필요한
  항목만 인용합니다.

[도구 사용 가이드]
- 커밋 hash나 Jira key를 모를 때: search_by_keyword로 진입점 탐색 후 다른 도구 호출
- 코드 변경 이유: search_by_keyword → get_changeset_context
- Jira 이슈 중심 탐색: get_issue_context 또는 get_timeline
- Slack 스레드 전체 맥락(검색 결과는 스레드당 대표 1건만 노출됨): get_thread_context(conversation_id)
- 파일 담당자: find_expert
- 사람 활동 조회: get_actor_activity
- 컨텍스트 없는 커밋: check_missing_context
- 여러 출처 비교: get_conflict_context
""".replace("__MIN_CONF__", f"{_MIN_CONFIDENCE:g}")

_FALLBACK_ANSWER = "답변을 생성하지 못했습니다."
_LLM_FAILURE_ANSWER = "AI 응답 생성에 실패했습니다. 잠시 후 다시 시도해주세요."

# 재시도로 회복되지 않는 클라이언트 오류(모델명 오타·미지원 파라미터·인증 실패 등).
# 이걸 삼키면 HTTP 200 + 빈 답변으로 위장돼 운영·eval 양쪽에서 조용한 장애가 된다
# (실례: QUERY_REASONING_EFFORT=low 설정 시 400이 로그에만 남고 9/9 질의가 빈 답변).
# 일시 오류(429·5xx·타임아웃)는 기존대로 삼키고 재시도 안내 답변으로 degrade한다.
_UNRECOVERABLE_STATUS = {400, 401, 403, 404, 422}


def _is_unrecoverable(exc: Exception) -> bool:
    return getattr(exc, "status_code", None) in _UNRECOVERABLE_STATUS


def _build_system_prompt(project_context: str = "") -> str:
    """프로젝트 컨텍스트가 있으면 시스템 프롬프트 상단에 도메인 정보를 주입한다."""
    if not project_context.strip():
        return _SYSTEM_PROMPT
    return (
        f"[프로젝트 컨텍스트]\n{project_context.strip()}\n"
        f"위 프로젝트의 도메인 용어와 일관되게 답변하고, 도구 호출 키워드도 도메인에 맞춰 선택하세요.\n\n"
        f"{_SYSTEM_PROMPT}"
    )


async def _call_llm(messages: list, with_tools: bool = True):
    """OpenAI tool calling 호출. 실패 시 None 반환."""
    kwargs = {**_model_kwargs(), "messages": messages}
    if with_tools:
        kwargs["tools"]       = TOOLS
        kwargs["tool_choice"] = "auto"
    try:
        return await chat_completion(priority=Priority.INTERACTIVE, **kwargs)
    except Exception as exc:
        if _is_unrecoverable(exc):
            raise
        logger.exception("LLM 호출 실패")
        return None


_STRUCTURED_FINAL_INSTRUCTION = (
    "현재 질문에서 수집한 도구 결과만 근거로, grounded_answer 스키마를 따라 최종 답변을 작성하세요. "
    "evidence[]에 등재할 수 없는 사실은 summary에 적지 마세요. "
    "그래프에서 확인되지 않은 측면이 있으면 unknown_aspects에 명시하세요."
)


def _record_usage(debug: dict | None, response) -> None:
    """debug가 주어지면 LLM 응답의 usage를 합산 누적한다 (eval 러너의 질의당 비용 기록용)."""
    if debug is None or response is None:
        return
    usage = getattr(response, "usage", None)
    if usage is None:
        return
    acc = debug.setdefault(
        "usage", {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0, "llm_calls": 0}
    )
    acc["prompt_tokens"] += getattr(usage, "prompt_tokens", 0) or 0
    acc["completion_tokens"] += getattr(usage, "completion_tokens", 0) or 0
    acc["total_tokens"] += getattr(usage, "total_tokens", 0) or 0
    acc["llm_calls"] += 1


def _record_tool_call(debug: dict | None, name: str, arguments, status: str, result: str | None) -> None:
    """debug가 주어지면 도구 호출 시도를 트랜스크립트로 기록한다 (파싱 실패·중복 차단 포함)."""
    if debug is None:
        return
    debug.setdefault("tool_calls", []).append(
        {"name": name, "arguments": arguments, "status": status, "result": result}
    )


async def _call_llm_structured(messages: list, debug: dict | None = None) -> dict | None:
    """tool calling이 끝난 뒤 최종 답변을 grounded_answer 스키마로 강제 생성.

    response_format=json_schema(strict=True)이라 LLM이 스키마 밖 자유 텍스트를 만들 수 없다.
    실패(JSON 파싱 실패 / API 오류) 시 None 반환 — 호출자가 fallback 처리.
    """
    final_messages = messages + [{"role": "user", "content": _STRUCTURED_FINAL_INSTRUCTION}]
    try:
        response = await chat_completion(
            priority=Priority.INTERACTIVE,
            **_model_kwargs(),
            messages=final_messages,
            response_format={"type": "json_schema", "json_schema": _GROUNDED_ANSWER_SCHEMA},
        )
    except Exception as exc:
        if _is_unrecoverable(exc):
            raise
        logger.exception("Structured LLM 호출 실패")
        return None
    _record_usage(debug, response)

    content = response.choices[0].message.content or ""
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        logger.warning("Structured 응답 JSON 파싱 실패: %s", content[:200])
        return None


async def summarize_history(
    running_summary: dict | None,
    history: list[dict[str, str]],
) -> dict | None:
    """기존 누적 요약에 새 대화 턴을 병합해 갱신한다."""
    messages = [
        {
            "role": "system",
            "content": (
                "대화 기억을 갱신하세요. 기존 누적 요약과 새 대화 턴을 합쳐 핵심 맥락을 보존하고, "
                "명시적으로 등장한 코드 변경 entity와 아직 해결되지 않은 질문을 구조화하세요. "
                "새 정보로 확인된 미해결 항목은 제거하고 추측은 추가하지 마세요."
            ),
        },
        {
            "role": "user",
            "content": json.dumps(
                {
                    "running_summary": running_summary,
                    "history": history,
                },
                ensure_ascii=False,
            ),
        },
    ]
    try:
        response = await chat_completion(
            priority=Priority.INTERACTIVE,
            **_model_kwargs(),
            messages=messages,
            response_format={"type": "json_schema", "json_schema": _RUNNING_SUMMARY_SCHEMA},
        )
    except Exception as exc:
        if _is_unrecoverable(exc):
            raise
        logger.exception("Running summary LLM 호출 실패")
        return None

    content = response.choices[0].message.content or ""
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        logger.warning("Running summary JSON 파싱 실패: %s", content[:200])
        return None


def _render_structured(structured: dict) -> str:
    """grounded_answer 구조를 사용자에게 보여줄 markdown으로 렌더한다.

    summary → '## 근거' → '## 확인되지 않은 측면' 순. 빈 섹션은 생략.
    """
    parts: list[str] = []

    summary = (structured.get("summary") or "").strip()
    if summary:
        parts.append(summary)

    evidence = structured.get("evidence") or []
    if evidence:
        parts.append("\n## 근거")
        for e in evidence:
            etype  = e.get("type", "?")
            eid    = e.get("id", "?")
            when   = e.get("occurredAt", "")
            meaning = e.get("event_meaning", "")
            author = e.get("author") or "—"
            header = f"- **[{etype}]** `{eid}` · {when} · {meaning} · {author}"
            parts.append(header)

            quote = (e.get("quote") or "").strip()
            if quote:
                if len(quote) > 300:
                    quote = quote[:300] + "…"
                # 멀티라인 quote는 markdown blockquote로 정렬
                quoted = "\n  > ".join(quote.splitlines())
                parts.append(f"  > {quoted}")

    unknown = structured.get("unknown_aspects") or []
    if unknown:
        parts.append("\n## 확인되지 않은 측면")
        for u in unknown:
            parts.append(f"- {u}")

    return "\n".join(parts) if parts else _FALLBACK_ANSWER


def _tool_error(tool_call_id: str, message: str) -> dict:
    """도구 호출 실패를 LLM에게 전달할 메시지 형태로 만든다."""
    return {
        "role": "tool",
        "tool_call_id": tool_call_id,
        "content": json.dumps({"error": message}, ensure_ascii=False),
    }


async def run(
    question: str,
    project_context: str = "",
    project_id: str = "",
    history: list[dict[str, str]] | None = None,
    prior_evidence: list[dict[str, str]] | None = None,
    running_summary: dict | None = None,
    focus_evidence: list[dict] | None = None,
    debug: dict | None = None,
) -> tuple[str, dict | None]:
    """자연어 질문을 받아 tool calling 루프로 답변을 생성해 반환.

    Tool calling이 끝난 뒤 grounded_answer Structured Output으로 한 번 더 호출해
    LLM이 evidence 없는 합성 문장을 만들지 못하도록 강제한다.

    project_id: 모든 도구 쿼리를 이 프로젝트로 스코프한다 (그래프 격리). backend가 인증된
                사용자의 프로젝트로 주입하며, LLM은 이 값에 접근하거나 변경할 수 없다.
    prior_evidence: 직전 응답의 대상 식별용 압축 근거. 도구 탐색에만 사용하고 최종 근거에서는 제외한다.
    running_summary: 최근 5턴보다 오래된 대화의 누적 요약. 탐색 맥락에만 사용하고 최종 근거에서는 제외한다.
    focus_evidence: 사용자가 관련 그래프에서 지정한 노드({type, id}). prior_evidence와 반대로
                    현재 턴에 두어 최종 근거까지 살리고, 유형별 도구로 먼저 조회하도록 지시한다.
    debug: eval 러너용 수집 dict. 주어지면 LLM usage 합산과 도구 호출 트랜스크립트를 여기에
           누적한다 (반환 계약은 불변 — 관측 전용, 답변 생성에 영향 없음).

    Returns:
        (markdown_answer, structured_dict)
        structured_dict는 schema 기반 dict (summary/evidence/unknown_aspects).
        Structured 호출 실패 시 LLM이 마지막 iteration에서 낸 자유 텍스트로 fallback,
        이때 structured는 None.
    """
    system_message = {"role": "system", "content": _build_system_prompt(project_context)}
    current_question = {"role": "user", "content": question}
    running_summary_message = None
    if running_summary:
        running_summary_message = {
            "role": "system",
            "content": (
                "오래된 대화의 누적 요약입니다. 후속 질문의 맥락과 대상을 식별하는 데만 사용하고, "
                "현재 답변의 근거로 인용하지 마세요. 필요한 사실은 도구로 다시 조회하세요.\n"
                + json.dumps(running_summary, ensure_ascii=False)
            ),
        }
    prior_evidence_message = None
    if prior_evidence:
        prior_evidence_message = {
            "role": "system",
            "content": (
                "이전 답변의 대상 식별 참고 정보입니다. 현재 답변의 근거로 인용하지 말고, "
                "후속 질문의 대상을 식별한 뒤 도구로 다시 조회하세요.\n"
                + json.dumps(prior_evidence, ensure_ascii=False)
            ),
        }
    focus_evidence_message = None
    if focus_evidence:
        focus_evidence_message = {
            "role": "system",
            "content": (
                "사용자가 아래 그래프 노드를 명시적으로 지정해 질문하고 있습니다. 이 노드를 답변의 "
                "핵심 대상으로 삼고, 각 노드의 전체 컨텍스트를 해당 도구로 먼저 조회한 뒤"
                "(commit→get_changeset_context, pull_request→get_pr_context, "
                "issue→get_issue_context, message→get_thread_context) 그 결과에 근거해 답하세요. "
                "지정된 노드를 무시하거나 다른 대상으로 대체하지 마세요.\n"
                + json.dumps(focus_evidence, ensure_ascii=False)
            ),
        }
    messages: list = [
        system_message,
        *([running_summary_message] if running_summary_message else []),
        *(history or []),
        *([prior_evidence_message] if prior_evidence_message else []),
    ]
    # focus 지시는 현재 턴에 둔다 — prior_evidence와 달리 최종 structured 답변까지 살아남아야
    # 지정 노드를 근거로 고정한다. current_turn_start를 focus 앞에 잡아 "지시 → 질문" 순으로 포함한다.
    current_turn_start = len(messages)
    if focus_evidence_message:
        messages.append(focus_evidence_message)
    messages.append(current_question)

    def current_turn_messages() -> list:
        # 누적 요약, 이전 대화, prior evidence는 현재 질문 앞에만 있으므로 최종 근거 생성에서 제외한다.
        return [messages[0], *messages[current_turn_start:]]

    seen_calls: set[tuple[str, str]] = set()  # (tool_name, args_json) 중복 호출 가드

    for iteration in range(_MAX_ITERATIONS):
        response = await _call_llm(messages, with_tools=True)
        if response is None:
            return _LLM_FAILURE_ANSWER, None
        _record_usage(debug, response)

        message = response.choices[0].message

        # tool_calls 없음 → 도구 탐색 종료. structured output으로 최종 답변 생성.
        if not message.tool_calls:
            structured = await _call_llm_structured(current_turn_messages(), debug=debug)
            if structured is None:
                # fallback: 마지막 LLM 자유 텍스트라도 반환 (할루시네이션 가드는 잃지만 응답은 제공)
                return message.content or _FALLBACK_ANSWER, None
            return _render_structured(structured), structured

        messages.append(message)

        for tc in message.tool_calls:
            tool_name = tc.function.name

            # 인자 JSON 파싱 — 실패 시 LLM이 다음 iteration에서 교정할 수 있게 명확히 알림
            try:
                args = json.loads(tc.function.arguments)
            except json.JSONDecodeError:
                logger.warning("도구 인자 JSON 파싱 실패: %s", tool_name)
                _record_tool_call(debug, tool_name, tc.function.arguments, "args_parse_error", None)
                error_message = _tool_error(
                    tc.id,
                    f"{tool_name} 인자 JSON 파싱 실패. 올바른 JSON 형식으로 다시 호출하세요.",
                )
                messages.append(error_message)
                continue

            # 중복 호출 가드 — 같은 인자로 같은 도구를 반복 호출하면 비용/컨텍스트 낭비
            call_key = (tool_name, json.dumps(args, sort_keys=True, ensure_ascii=False))
            if call_key in seen_calls:
                logger.info("중복 도구 호출 차단: %s args=%s", tool_name, args)
                _record_tool_call(debug, tool_name, args, "duplicate", None)
                error_message = _tool_error(
                    tc.id,
                    f"{tool_name}을(를) 동일한 인자로 이미 호출했습니다. 이전 결과를 참고하거나 다른 인자/도구를 시도하세요.",
                )
                messages.append(error_message)
                continue
            seen_calls.add(call_key)

            logger.info("도구 호출: %s", tool_name)
            result_str = await execute(tool_name, args, project_id, question=question)
            logger.debug("도구 결과: %s → %d자", tool_name, len(result_str))
            _record_tool_call(debug, tool_name, args, "ok", result_str)

            tool_message = {
                "role": "tool",
                "tool_call_id": tc.id,
                "content": result_str,
            }
            messages.append(tool_message)

    # 최대 반복 도달 → 그 시점까지의 컨텍스트로 강제 structured 답변
    logger.warning("최대 반복 횟수(%d) 도달 — structured 강제 응답", _MAX_ITERATIONS)
    structured = await _call_llm_structured(current_turn_messages(), debug=debug)
    if structured is None:
        return _FALLBACK_ANSWER, None
    return _render_structured(structured), structured
