package com.history.backend.graph.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;

// ai-engine GET /graph/actors 목록 1건 — 수동 병합/분리 대상 선택용.
// ai-engine은 snake_case로 응답하지만 frontend에는 backend 표준 camelCase로 직렬화한다.
public record ActorResponse(
        String uuid,
        String name,
        List<String> aliases,
        List<String> emails,
        Double confidence,
        @JsonAlias("activity_count") Long activityCount
) {
}
