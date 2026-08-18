package com.history.backend.graph.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

// ai-engine POST /actors/split 응답 — 분리 결과 요약.
public record ActorSplitResponse(
        @JsonAlias("new_uuid") String newUuid,
        @JsonAlias("new_name") String newName,
        @JsonAlias("moved_edges") Integer movedEdges,
        @JsonAlias("moved_sources") List<String> movedSources,
        @JsonAlias("distinct_decision_id") String distinctDecisionId
) {
}
