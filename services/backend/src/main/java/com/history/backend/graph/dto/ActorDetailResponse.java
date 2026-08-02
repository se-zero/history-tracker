package com.history.backend.graph.dto;

import java.util.List;

// ai-engine GET /graph/actors/{uuid} 응답 — 병합/분리 폼용 상세 조회(alias 전체 목록 포함).
public record ActorDetailResponse(String uuid, String name, List<ActorAliasDetail> aliases) {
}
