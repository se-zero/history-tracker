package com.history.backend.github.dto;

import java.util.List;

public record GitHubInstallationsResponse(
        List<GitHubInstallationResponse> installations
) {
}
