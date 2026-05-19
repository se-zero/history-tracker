package com.history.backend.github.dto;

import java.util.List;

public record GitHubRepositoriesResponse(
        List<GitHubRepositoryResponse> repositories
) {
}
