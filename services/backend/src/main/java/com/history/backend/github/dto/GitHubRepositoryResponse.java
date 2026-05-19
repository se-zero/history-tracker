package com.history.backend.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubRepositoryResponse(
        Long id,
        String name,
        @JsonProperty("full_name")
        String fullName,
        GitHubRepositoryOwnerResponse owner,
        @JsonProperty("private")
        boolean privateRepository,
        String visibility,
        @JsonProperty("default_branch")
        String defaultBranch
) {
}
