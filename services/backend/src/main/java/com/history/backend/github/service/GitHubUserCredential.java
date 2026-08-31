package com.history.backend.github.service;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

// GitHub 사용자 OAuth 자격증명 — encrypted_credential(BYTEA)에 JSON으로 직렬화해 저장한다.
// installation token과 달리 로그인 사용자 단위라 refresh·만료 시각이 함께 필요하다.
public record GitHubUserCredential(
        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("expires_at")
        Instant expiresAt,

        @JsonProperty("refresh_token_expires_at")
        Instant refreshTokenExpiresAt
) {
}
