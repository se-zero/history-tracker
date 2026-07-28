package com.history.backend.graph.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

// 성좌 뷰용 그래프. overview와 달리 작업 단위(PR, 없으면 Issue)를 전량 담고 위성만 최신 N개로 자른다.
// workUnitIds는 별성으로 그릴 노드 id — 어떤 노드가 작업 단위인지는 ai-engine이 정해서 알려준다.
// ai-engine은 snake_case로 응답하므로 @JsonProperty로 매핑한다.
public record GraphConstellationResponse(
        @JsonProperty("nodes") List<GraphNodeResponse> nodes,
        @JsonProperty("edges") List<List<String>> edges,
        @JsonProperty("work_unit_ids") List<String> workUnitIds
) {

    public static GraphConstellationResponse empty() {
        return new GraphConstellationResponse(List.of(), List.of(), List.of());
    }
}
