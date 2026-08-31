package com.history.backend.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubAccessTokenResponse(
        @JsonProperty("access_token")
        String accessToken,
        @JsonProperty("token_type")
        String tokenType,
        String scope,
        @JsonProperty("refresh_token")
        String refreshToken,
        @JsonProperty("expires_in")
        Long expiresIn,
        @JsonProperty("refresh_token_expires_in")
        Long refreshTokenExpiresIn,
        String error
) {
    // 테스트·호출부가 error 없이 성공 응답만 조립할 때 쓴다. Jackson은 아래 canonical을 쓴다.
    public GitHubAccessTokenResponse(
            String accessToken,
            String tokenType,
            String scope,
            String refreshToken,
            Long expiresIn,
            Long refreshTokenExpiresIn
    ) {
        this(accessToken, tokenType, scope, refreshToken, expiresIn, refreshTokenExpiresIn, null);
    }
}
