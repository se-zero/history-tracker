package com.history.backend.security;

import java.time.Instant;
import java.util.UUID;

// JWT 토큰에서 추출한 클레임을 담는 객체
public record JwtClaims(
        UUID subject,
        String tokenType,
        Instant issuedAt,
        Instant expiresAt
) {
}
