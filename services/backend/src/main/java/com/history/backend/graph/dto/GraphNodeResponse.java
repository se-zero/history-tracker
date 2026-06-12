package com.history.backend.graph.dto;

// ai-engine /graph/overview 노드 1건. 프론트 GraphNode와 동일 형태로 그대로 전달한다.
public record GraphNodeResponse(
        String id,
        String type,
        String title,
        String meta,
        String source,
        String snippet
) {
}
