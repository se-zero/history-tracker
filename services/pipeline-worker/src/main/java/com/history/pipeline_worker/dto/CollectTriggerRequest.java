package com.history.pipeline_worker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record CollectTriggerRequest(
        @NotBlank
        @Pattern(regexp = PROJECT_ID_PATTERN)
        String projectId
) {

    private static final String PROJECT_ID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    public UUID projectUuid() {
        return UUID.fromString(projectId);
    }
}
