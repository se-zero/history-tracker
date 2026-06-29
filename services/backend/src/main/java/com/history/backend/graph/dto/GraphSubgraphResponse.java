package com.history.backend.graph.dto;

import java.util.List;

// 답변 evidence 관련 서브그래프 — nodes/edges는 GraphResponse와 동일, seeds는 입력 evidence
// 순서에 정렬된 시드 노드 id(미해석은 null, 인용 카드 ↔ 노드 매핑용).
public record GraphSubgraphResponse(
        List<GraphNodeResponse> nodes,
        List<List<String>> edges,
        List<String> seeds
) {

    public static GraphSubgraphResponse empty() {
        return new GraphSubgraphResponse(List.of(), List.of(), List.of());
    }
}
