package com.history.backend.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConnectJiraIntegrationRequest(
        @JsonProperty("base_url")
        @NotBlank
        @Size(max = 2048)
        @Pattern(regexp = "^https://.+", message = "must start with https://")
        String baseUrl,

        @JsonProperty("project_key")
        @NotBlank
        @Size(max = 10)
        @Pattern(regexp = "^[A-Z][A-Z0-9]{1,9}$", message = "must be a Jira project key")
        String projectKey,

        @JsonProperty("email")
        @NotBlank
        @Email
        @Size(max = 320)
        String email,

        @JsonProperty("api_token")
        @NotBlank
        @Size(max = 4096)
        String apiToken
) {
}
