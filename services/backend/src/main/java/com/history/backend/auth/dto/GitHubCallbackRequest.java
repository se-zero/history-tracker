package com.history.backend.auth.dto;

public record GitHubCallbackRequest(
        String code,
        String state,
        Long installationId
) {
}
