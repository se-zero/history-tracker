package com.history.backend.graph.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

// ai-engine POST /graph/subgraph 요청 본문 — project_id 스코프 + evidence 목록.
public record AiEngineSubgraphRequest(
        @JsonProperty("project_id") String projectId,
        @JsonProperty("evidence") List<EvidenceRef> evidence
) {
}
