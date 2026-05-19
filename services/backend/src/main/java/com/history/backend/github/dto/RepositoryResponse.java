package com.history.backend.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RepositoryResponse(
        Long id,
        String name,
        @JsonProperty("full_name")
        String fullName,
        String owner,
        @JsonProperty("private")
        boolean privateRepository,
        String visibility,
        @JsonProperty("default_branch")
        String defaultBranch
) {
    public static RepositoryResponse from(GitHubRepositoryResponse repository) {
        String ownerLogin = repository.owner() == null ? null : repository.owner().login();
        return new RepositoryResponse(
                repository.id(),
                repository.name(),
                repository.fullName(),
                ownerLogin,
                repository.privateRepository(),
                repository.visibility(),
                repository.defaultBranch()
        );
    }
}
