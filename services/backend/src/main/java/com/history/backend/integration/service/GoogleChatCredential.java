package com.history.backend.integration.service;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

// Google Chat OAuth 자격증명 3종 — encrypted_credential(BYTEA)에 JSON으로 직렬화해 저장한다
public record GoogleChatCredential(
        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("expires_at")
        Instant expiresAt
) {
}
