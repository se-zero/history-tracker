package com.history.backend.github.dto;

public record GitHubInstallationResponse(
        Long id,
        GitHubInstallationAccountResponse account
) {
}
