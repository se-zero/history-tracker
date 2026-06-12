package com.history.backend.conversation.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiEngineQueryRequest(
        String question,
        @JsonProperty("project_id") String projectId,
        List<AiEngineHistoryMessage> history
) {
}
