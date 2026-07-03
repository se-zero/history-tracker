package com.history.backend.project.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;

public record ReorderProjectsRequest(
        @NotEmpty List<UUID> orderedIds
) {
}
