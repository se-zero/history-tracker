package com.history.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.history.backend.auth.dto.TokenResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IssuedSession: JSON 바디에는 access만 남긴다")
class IssuedSessionTest {

    @Test
    @DisplayName("toResponse는 refresh 원문을 바디에 넣지 않는다")
    void toResponseOmitsRefreshToken() {
        IssuedSession session = new IssuedSession(
                "access-token",
                "refresh-token",
                900,
                Duration.ofDays(14)
        );

        TokenResponse body = session.toResponse();

        assertThat(body.accessToken()).isEqualTo("access-token");
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.expiresIn()).isEqualTo(900);
        assertThat(session.refreshToken()).isEqualTo("refresh-token");
    }
}
