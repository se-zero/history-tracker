package com.history.pipeline_worker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public record NormalizeFetchRequest(
        @NotBlank
        @Pattern(regexp = PROJECT_ID_PATTERN)
        String projectId,

        @NotBlank
        String credentials,

        String projectKey,

        Map<String, String> options
) {

    public static final String PROJECT_ID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    public RawFetchRequest toRawFetchRequest() {
        return new RawFetchRequest(credentials, projectKey, options);
    }
}
