package com.history.backend.graph.dto;

// 답변 evidence 1건 — 도메인 키로 그래프 노드를 가리킨다(commit hash·#PR·issue_key·conversation_id).
// 프론트 요청·ai-engine 요청 양방향에서 동일 형태로 쓰인다(type/id 모두 단순 소문자라 별도 매핑 불필요).
public record EvidenceRef(
        String type,
        String id
) {
}
