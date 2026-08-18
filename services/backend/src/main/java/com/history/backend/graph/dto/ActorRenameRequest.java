package com.history.backend.graph.dto;

public record ActorRenameRequest(
        String actorUuid,
        String name
) {
}
