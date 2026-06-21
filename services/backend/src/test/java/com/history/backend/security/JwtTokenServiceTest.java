package com.history.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwtTokenService: JWT 토큰 발급·검증")
class JwtTokenServiceTest {

    private static final UUID USER_ID = UUID.fromString("1f337cd1-cb32-4a9e-8d95-0cc656c974f8");

    @Test
    @DisplayName("액세스 토큰 발급 후 검증 성공")
    void issueAndValidateAccessToken() {
        JwtTokenService tokenService = tokenService(Duration.ofMinutes(15));

        String token = tokenService.issueAccessToken(USER_ID);

        assertThat(tokenService.validateAccessToken(token))
                .isEqualTo(new AuthenticatedUser(USER_ID));
    }

    @Test
    @DisplayName("변조된 토큰 검증 실패")
    void rejectTamperedToken() {
        JwtTokenService tokenService = tokenService(Duration.ofMinutes(15));
        String token = tokenService.issueAccessToken(USER_ID);

        String[] parts = token.split("\\.");
        String tamperedPayload = (parts[1].charAt(0) == 'e' ? "a" : "e") + parts[1].substring(1);
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThrows(JwtAuthenticationException.class, () -> tokenService.validateAccessToken(tamperedToken));
    }

    @Test
    @DisplayName("만료된 액세스 토큰 검증 실패")
    void rejectExpiredAccessToken() {
        JwtTokenService tokenService = tokenService(Duration.ofSeconds(-1));
        String token = tokenService.issueAccessToken(USER_ID);

        assertThrows(JwtAuthenticationException.class, () -> tokenService.validateAccessToken(token));
    }

    private JwtTokenService tokenService(Duration accessTokenTtl) {
        return new JwtTokenService(new JwtProperties("test-secret", accessTokenTtl, Duration.ofDays(14)));
    }
}
