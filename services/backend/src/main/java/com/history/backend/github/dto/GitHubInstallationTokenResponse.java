package com.history.backend.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubInstallationTokenResponse(
        String token,
        // Used by the token-cache phase when persisting installation token expiry.
        @JsonProperty("expires_at")
        String expiresAt
) {
}
