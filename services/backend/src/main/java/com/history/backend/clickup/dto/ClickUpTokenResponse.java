package com.history.backend.clickup.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClickUpTokenResponse(
        @JsonProperty("access_token")
        String accessToken
) {
}
