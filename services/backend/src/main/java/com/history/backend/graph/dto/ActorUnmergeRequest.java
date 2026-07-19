package com.history.backend.graph.dto;

// 프론트 요청 — same 병합 결정을 스냅샷 기준으로 복원.
public record ActorUnmergeRequest(String decisionId) {
}
