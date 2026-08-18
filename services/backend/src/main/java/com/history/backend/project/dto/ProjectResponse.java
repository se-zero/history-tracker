package com.history.backend.project.dto;

import java.time.Instant;
import java.util.UUID;

import com.history.backend.project.domain.Project;

public record ProjectResponse(
        UUID id,
        UUID ownerId,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt
) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getOwner().getId(),
                project.getName(),
                project.getDescription(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
