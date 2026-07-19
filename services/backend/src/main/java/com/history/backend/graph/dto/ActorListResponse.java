package com.history.backend.graph.dto;

import java.util.List;

public record ActorListResponse(List<ActorResponse> actors) {

    public static ActorListResponse empty() {
        return new ActorListResponse(List.of());
    }
}
