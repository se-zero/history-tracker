package com.history.backend.graph.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

// ai-engine GET /actors/decisions 1건 — 수동 병합(same)/분리(distinct) 결정 이력.
// same 결정은 unmerge 대상, distinct 결정은 철회(DELETE) 대상이다.
// aliasesA/aliasesB는 계정ID가 아니라 사람이 읽을 수 있는 소스별 이름 목록이다.
public record ActorDecisionResponse(
        @JsonAlias("decision_id") String decisionId,
        String kind,
        @JsonAlias("aliases_a") List<ActorSourceName> aliasesA,
        @JsonAlias("aliases_b") List<ActorSourceName> aliasesB,
        @JsonAlias("canonical_uuid") String canonicalUuid,
        String note,
        @JsonAlias("decided_at") String decidedAt
) {
}
