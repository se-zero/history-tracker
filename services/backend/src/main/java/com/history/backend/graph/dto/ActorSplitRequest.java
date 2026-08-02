package com.history.backend.graph.dto;

import java.util.List;

// 프론트 요청 — 자동 병합이 잘못 합친 Actor에서 alias 일부를 새 Actor로 분리.
// 표시 이름은 병합과 마찬가지로 ai-engine이 유도 규칙으로 자동 결정한다.
public record ActorSplitRequest(String actorUuid, List<String> sourceIds) {
}
