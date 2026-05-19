package com.history.backend.integration.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ConnectGitHubIntegrationRequest(
        @JsonProperty("installation_id")
        @NotNull
        UUID installationId,

        @JsonProperty("repository_id")
        @NotNull
        @Positive
        Long repositoryId,

        @JsonProperty("repository_full_name")
        @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "^[^/\\s]+/[^/\\s]+$", message = "must be 'owner/repo'")
        String repositoryFullName
) {
}
