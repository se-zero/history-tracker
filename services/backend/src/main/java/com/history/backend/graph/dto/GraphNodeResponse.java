package com.history.backend.graph.dto;

// ai-engine /graph/overview 노드 1건. 프론트 GraphNode와 동일 형태로 그대로 전달한다.
// ref는 focus 질의용 도메인 키(commit hash·#PR·jira_key·conversation_id) — 질의 도구 대상이 아닌 노드(actor/file)는 null.
public record GraphNodeResponse(
        String id,
        String type,
        String title,
        String meta,
        String source,
        String snippet,
        EvidenceRef ref
) {
}
