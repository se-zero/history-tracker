package com.history.backend.graph.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

// ai-engine GET /actors/decisions 1건 — 수동 병합(same)/분리(distinct) 결정 이력.
// same 결정은 unmerge 대상, distinct 결정은 철회(DELETE) 대상이다.
public record ActorDecisionResponse(
        @JsonAlias("decision_id") String decisionId,
        String kind,
        @JsonAlias("aliases_a") List<String> aliasesA,
        @JsonAlias("aliases_b") List<String> aliasesB,
        @JsonAlias("canonical_uuid") String canonicalUuid,
        String note,
        @JsonAlias("decided_at") String decidedAt
) {
}
