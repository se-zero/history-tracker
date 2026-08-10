package com.history.backend.asana.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AsanaTokenResponse(
        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("expires_in")
        Long expiresIn
) {
}
