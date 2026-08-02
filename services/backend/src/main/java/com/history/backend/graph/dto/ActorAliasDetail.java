package com.history.backend.graph.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

// ai-engine GET /graph/actors/{uuid} 응답의 alias 1건 — 병합/분리 폼에서 소스별 원본 신원을 보여준다.
public record ActorAliasDetail(
        @JsonAlias("source_id") String sourceId,
        String source,
        String name,
        String email,
        String erased
) {
}
