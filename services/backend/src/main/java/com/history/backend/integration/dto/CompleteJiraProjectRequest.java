package com.history.backend.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CompleteJiraProjectRequest(
        @JsonProperty("cloud_id")
        @NotBlank
        String cloudId,

        @JsonProperty("site_name")
        @NotBlank
        String siteName,

        @JsonProperty("project_key")
        @NotBlank
        String projectKey,

        @JsonProperty("project_name")
        @NotBlank
        String projectName
) {
}
