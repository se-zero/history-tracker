package com.history.backend.integration.service;

import com.fasterxml.jackson.annotation.JsonProperty;

// ClickUp OAuth 자격증명 — access token은 만료·회전이 없어 access_token 하나만 담는다
public record ClickUpCredential(
        @JsonProperty("access_token")
        String accessToken
) {
}
