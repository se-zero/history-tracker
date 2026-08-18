package com.history.backend.graph.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

// ai-engine POST /actors/unmerge 응답 — 복원 결과 요약.
public record ActorUnmergeResponse(
        @JsonAlias("restored_uuid") String restoredUuid,
        @JsonAlias("canonical_uuid") String canonicalUuid,
        @JsonAlias("moved_edges") Integer movedEdges,
        @JsonAlias("distinct_decision_id") String distinctDecisionId
) {
}
