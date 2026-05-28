package com.history.pipeline_worker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public record NormalizeFetchRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String projectId,

        @NotBlank
        String credentials,

        String projectKey,

        Map<String, String> options
) {

    public RawFetchRequest toRawFetchRequest() {
        return new RawFetchRequest(credentials, projectKey, options);
    }
}
