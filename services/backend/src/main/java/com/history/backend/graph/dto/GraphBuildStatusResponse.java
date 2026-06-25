package com.history.backend.graph.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// ai-engine의 per-project 비동기 빌드 상태 — POST /graph/build(202)·GET /graph/build/status 응답.
// state: idle(빌드 이력 없음) | running | succeeded | failed.
// result는 succeeded일 때만 단계별 카운트가 채워지고, error는 failed일 때만 사유가 담긴다.
// ai-engine은 snake_case로 응답하므로 @JsonProperty로 매핑한다.
public record GraphBuildStatusResponse(
        @JsonProperty("state") String state,
        @JsonProperty("verify") Boolean verify,
        @JsonProperty("started_at") String startedAt,
        @JsonProperty("result") GraphBuildResponse result,
        @JsonProperty("error") String error
) {
}
