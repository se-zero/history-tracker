package com.history.backend.conversation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiEngineQueryRequest(
        String question,
        // ai-engine은 snake_case로 받는다. 그래프 격리 스코프 — 인증된 사용자의 프로젝트.
        @JsonProperty("project_id") String projectId
) {
}
