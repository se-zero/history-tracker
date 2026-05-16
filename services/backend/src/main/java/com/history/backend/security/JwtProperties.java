package com.history.backend.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// JWT 토큰 생성과 검증에 필요한 설정값을 담는 클래스
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
}
