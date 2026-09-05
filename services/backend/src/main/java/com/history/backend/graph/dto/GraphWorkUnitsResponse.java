package com.history.backend.graph.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

// 작업 단위 뷰용 그래프. overview와 달리 작업 단위(PR, 없으면 Issue)를 전량 담고 구성 노드만 최신 N개로 자른다.
// workUnitIds는 작업 단위로 그릴 노드 id — 어떤 노드가 작업 단위인지는 ai-engine이 정해서 알려준다.
// ai-engine은 snake_case로 응답하므로 @JsonProperty로 매핑한다.
public record GraphWorkUnitsResponse(
        @JsonProperty("nodes") List<GraphNodeResponse> nodes,
        @JsonProperty("edges") List<GraphEdgeResponse> edges,
        @JsonProperty("work_unit_ids") List<String> workUnitIds
) {

    public static GraphWorkUnitsResponse empty() {
        return new GraphWorkUnitsResponse(List.of(), List.of(), List.of());
    }
}
