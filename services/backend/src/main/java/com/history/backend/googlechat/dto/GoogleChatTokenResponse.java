package com.history.backend.googlechat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Google OAuth2 토큰 교환/갱신 응답. refresh_token은 최초 교환(access_type=offline + prompt=consent
// 동의 시)에만 오고, 갱신 응답에는 다시 오지 않는다(회전하지 않음) — 갱신 시 기존 저장값을 보존해야
// 한다(GoogleChatTokenService).
public record GoogleChatTokenResponse(
        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("expires_in")
        Long expiresIn
) {
}
