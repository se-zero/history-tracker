"""AI 질의 API의 요청 데이터 모델을 정의한다."""

from typing import Literal

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


class QueryRequest(BaseModel):
    question: str
    project_id: str = ""
    history: list[HistoryMessage] = Field(default_factory=list)
    repo: str = ""
