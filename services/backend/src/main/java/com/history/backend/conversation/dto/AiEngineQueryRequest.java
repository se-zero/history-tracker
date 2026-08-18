package com.history.backend.conversation.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.history.backend.graph.dto.EvidenceRef;

public record AiEngineQueryRequest(
        String question,
        @JsonProperty("project_id") String projectId,
        List<AiEngineHistoryMessage> history,
        @JsonProperty("prior_evidence") List<AiEnginePriorEvidence> priorEvidence,
        @JsonProperty("running_summary") Map<String, Object> runningSummary,
        @JsonProperty("focus_evidence") List<EvidenceRef> focusEvidence
) {
}
