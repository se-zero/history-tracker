package com.history.backend.graph.dto;

// 프론트 요청 — 두 노드(sourceUuid, targetUuid)를 같은 사람으로 합치고 name을 표시 이름으로 정한다.
public record ActorMergeRequest(String sourceUuid, String targetUuid, String name, String note) {
}
