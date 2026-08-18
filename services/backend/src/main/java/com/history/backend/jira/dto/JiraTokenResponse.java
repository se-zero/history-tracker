package com.history.backend.jira.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JiraTokenResponse(
        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("expires_in")
        Long expiresIn
) {
}
