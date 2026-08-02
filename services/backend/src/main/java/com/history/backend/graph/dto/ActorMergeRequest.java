package com.history.backend.graph.dto;

// 프론트 요청 — 두 노드(uuidA, uuidB)를 같은 사람으로 합친다. 병합 방향과 표시 이름은
// ai-engine이 활동량 등을 기준으로 자동 결정한다.
public record ActorMergeRequest(String uuidA, String uuidB, String note) {
}
