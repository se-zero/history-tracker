package com.history.backend.graph.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

// ai-engine POST /actors/merge 응답 — 병합 결과 요약.
public record ActorMergeResponse(
        @JsonAlias("decision_id") String decisionId,
        @JsonAlias("canonical_uuid") String canonicalUuid,
        @JsonAlias("merged_uuid") String mergedUuid,
        @JsonAlias("moved_edges") Integer movedEdges,
        List<String> aliases
) {
}
