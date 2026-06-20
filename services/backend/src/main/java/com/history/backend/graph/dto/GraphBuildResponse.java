package com.history.backend.graph.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// ai-engine POST /graph/build 응답 — 후처리(Layer 4) 단계별로 생성/갱신된 엣지 수.
// ai-engine은 snake_case로 응답하므로 @JsonProperty로 매핑한다.
public record GraphBuildResponse(
        @JsonProperty("backfilled") int backfilled,
        @JsonProperty("triggered_by") int triggeredBy,
        @JsonProperty("discussed_in") int discussedIn,
        @JsonProperty("reference") int reference,
        @JsonProperty("thread_propagated") int threadPropagated
) {
}
