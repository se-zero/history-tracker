package com.history.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private static final UUID USER_ID = UUID.fromString("1f337cd1-cb32-4a9e-8d95-0cc656c974f8");

    @Test
    void issueAndValidateAccessToken() {
        JwtTokenService tokenService = tokenService(Duration.ofMinutes(15));

        String token = tokenService.issueAccessToken(USER_ID);

        assertThat(tokenService.validateAccessToken(token))
                .isEqualTo(new AuthenticatedUser(USER_ID));
    }

    @Test
    void rejectTamperedToken() {
        JwtTokenService tokenService = tokenService(Duration.ofMinutes(15));
        String token = tokenService.issueAccessToken(USER_ID);

        String tamperedToken = token.substring(0, token.length() - 1) + "x";

        assertThrows(JwtAuthenticationException.class, () -> tokenService.validateAccessToken(tamperedToken));
    }

    @Test
    void rejectExpiredAccessToken() {
        JwtTokenService tokenService = tokenService(Duration.ofSeconds(-1));
        String token = tokenService.issueAccessToken(USER_ID);

        assertThrows(JwtAuthenticationException.class, () -> tokenService.validateAccessToken(token));
    }

    private JwtTokenService tokenService(Duration accessTokenTtl) {
        return new JwtTokenService(new JwtProperties("test-secret", accessTokenTtl, Duration.ofDays(14)));
    }
}
