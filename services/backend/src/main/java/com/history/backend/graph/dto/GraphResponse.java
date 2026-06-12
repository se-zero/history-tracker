package com.history.backend.graph.dto;

import java.util.List;

// 프로젝트 그래프 개요. edges는 [sourceNodeId, targetNodeId] 쌍의 목록.
public record GraphResponse(
        List<GraphNodeResponse> nodes,
        List<List<String>> edges
) {

    public static GraphResponse empty() {
        return new GraphResponse(List.of(), List.of());
    }
}
