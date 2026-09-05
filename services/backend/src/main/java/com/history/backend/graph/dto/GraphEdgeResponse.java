package com.history.backend.graph.dto;

// ai-engine /graph/* 엣지 1건. 필드명이 ai-engine과 동일해 @JsonProperty가 불필요하다.
// method/confidence/section은 구조 관계(CONTAINS 등)에서 키 자체가 없거나 값이 null이다.
public record GraphEdgeResponse(
        String source,
        String target,
        String kind,
        String method,
        Double confidence,
        String section
) {
}
