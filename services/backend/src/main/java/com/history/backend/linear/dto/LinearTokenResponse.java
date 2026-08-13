package com.history.backend.linear.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LinearTokenResponse(
        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("expires_in")
        Long expiresIn
) {
}
