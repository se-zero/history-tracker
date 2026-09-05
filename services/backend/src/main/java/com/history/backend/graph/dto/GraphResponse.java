package com.history.backend.graph.dto;

import java.util.List;

// 프로젝트 그래프 개요. edges는 GraphEdgeResponse(엣지 관계·판별 방식·신뢰도) 목록.
public record GraphResponse(
        List<GraphNodeResponse> nodes,
        List<GraphEdgeResponse> edges
) {

    public static GraphResponse empty() {
        return new GraphResponse(List.of(), List.of());
    }
}
