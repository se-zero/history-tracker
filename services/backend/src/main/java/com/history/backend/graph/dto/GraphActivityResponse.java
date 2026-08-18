package com.history.backend.graph.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// ai-engine의 프로젝트 그래프 활동 상태 — GET /graph/activity 응답 (프론트 채팅 게이팅용).
// state: idle | collecting(최초 수집중) | building(수동 재구축중).
// 빌드 상태(GraphBuildStatusResponse)와 별개 신호다 — 프론트는 idle이 아니면 질문을 막는다.
public record GraphActivityResponse(
        @JsonProperty("state") String state
) {
}
