package com.history.backend.graph.dto;

import java.util.List;

public record ActorDecisionListResponse(List<ActorDecisionResponse> decisions) {

    public static ActorDecisionListResponse empty() {
        return new ActorDecisionListResponse(List.of());
    }
}
