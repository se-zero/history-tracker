package com.history.backend.conversation.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiEngineSummaryRequest(
        @JsonProperty("running_summary") Map<String, Object> runningSummary,
        List<AiEngineHistoryMessage> history
) {
}
