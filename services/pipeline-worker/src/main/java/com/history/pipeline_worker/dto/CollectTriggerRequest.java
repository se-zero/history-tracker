package com.history.pipeline_worker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record CollectTriggerRequest(
        @NotBlank
        @Pattern(regexp = NormalizeFetchRequest.PROJECT_ID_PATTERN)
        String projectId
) {

    public UUID projectUuid() {
        return UUID.fromString(projectId);
    }
}
