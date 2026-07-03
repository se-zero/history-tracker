package com.history.backend.graph.dto;

import java.util.List;

// 그래프 노드 키워드 검색 결과. 노드는 overview와 동일 형태(GraphNodeResponse)로 그대로 전달한다.
public record GraphSearchResponse(List<GraphNodeResponse> nodes) {

    public static GraphSearchResponse empty() {
        return new GraphSearchResponse(List.of());
    }
}
