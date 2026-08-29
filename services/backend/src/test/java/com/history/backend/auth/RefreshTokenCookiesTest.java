package com.history.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

@DisplayName("RefreshTokenCookies: refresh httpOnly 쿠키 속성")
class RefreshTokenCookiesTest {

    @Test
    @DisplayName("발급 쿠키는 HttpOnly·SameSite=Lax·auth 경로·요청한 수명")
    void issueSetsHttpOnlyLaxCookieOnAuthPath() {
        ResponseCookie cookie = RefreshTokenCookies.issue("raw-refresh", false, Duration.ofDays(14));

        assertThat(cookie.getName()).isEqualTo("ht_refresh");
        assertThat(cookie.getValue()).isEqualTo("raw-refresh");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(14));
        assertThat(cookie.isSecure()).isFalse();
    }

    @Test
    @DisplayName("HTTPS 요청이면 Secure 플래그를 켠다")
    void issueSetsSecureWhenRequestIsHttps() {
        ResponseCookie cookie = RefreshTokenCookies.issue("raw-refresh", true, Duration.ofDays(14));

        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    @DisplayName("삭제 쿠키는 값은 비우고 Max-Age=0")
    void clearExpiresCookieImmediately() {
        ResponseCookie cookie = RefreshTokenCookies.clear(false);

        assertThat(cookie.getName()).isEqualTo("ht_refresh");
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
    }
}
