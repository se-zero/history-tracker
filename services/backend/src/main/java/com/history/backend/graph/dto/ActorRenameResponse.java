package com.history.backend.graph.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

// ai-engine POST /actors/rename 응답 — 변경된 Actor 표시 이름.
public record ActorRenameResponse(
        String uuid,
        String name,
        @JsonAlias("normalized_name") String normalizedName
) {
}
