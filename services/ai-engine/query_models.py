"""AI 질의 API의 요청 데이터 모델을 정의한다."""

from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str

    @field_validator("content")
    @classmethod
    def content_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("history content must not be blank")
        return value


class PriorEvidence(BaseModel):
    type: Literal["commit", "pull_request", "issue", "message"]
    id: str
    quote: str

    @field_validator("id", "quote")
    @classmethod
    def value_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("prior evidence value must not be blank")
        return value


# 사용자가 관련 그래프에서 지정한 focus 노드의 도메인 키. 노드 투영이 방출한 ref를 그대로 받는다.
# PriorEvidence와 달리 관대하게(quote 없음, blank 허용) 둔다 — 프론트 ref가 어긋나도 422로 막지 않는다.
class FocusEvidence(BaseModel):
    type: str = ""
    id: str = ""


class SummaryRequest(BaseModel):
    running_summary: dict[str, Any] | None = None
    history: list[HistoryMessage]


class QueryRequest(BaseModel):
    question: str
    project_id: str = ""
    history: list[HistoryMessage] = Field(default_factory=list)
    prior_evidence: list[PriorEvidence] = Field(default_factory=list)
    focus_evidence: list[FocusEvidence] = Field(default_factory=list)
    running_summary: dict[str, Any] | None = None
    # eval 러너용 — true면 응답에 debug(토큰 usage 합산·도구 호출 트랜스크립트)를 붙인다.
    include_debug: bool = False
