package com.history.backend.auth.service;

import java.time.Duration;

import com.history.backend.auth.dto.TokenResponse;

// 로그인·refresh 직후 컨트롤러가 JSON(body)과 Set-Cookie로 나눌 값. refresh 원문은 응답 바디에 넣지 않는다.
public record IssuedSession(
        String accessToken,
        String refreshToken,
        long expiresIn,
        Duration refreshTokenTtl
) {

    public TokenResponse toResponse() {
        return new TokenResponse(accessToken, "Bearer", expiresIn);
    }
}
