package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

@DisplayName("ConsentTicketCookies: 동의 티켓 httpOnly 쿠키 속성")
class ConsentTicketCookiesTest {

    @Test
    @DisplayName("발급 쿠키는 HttpOnly·SameSite=Lax·authorize 경로·60초")
    void issueSetsHttpOnlyLaxCookieOnAuthorizePath() {
        ResponseCookie cookie = ConsentTicketCookies.issue("ticket-value", false);

        assertThat(cookie.getName()).isEqualTo("wc_oauth_ticket");
        assertThat(cookie.getValue()).isEqualTo("ticket-value");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/oauth2/authorize");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofSeconds(60));
        assertThat(cookie.isSecure()).isFalse();
    }

    @Test
    @DisplayName("HTTPS 요청이면 Secure 플래그를 켠다")
    void issueSetsSecureWhenRequestIsHttps() {
        ResponseCookie cookie = ConsentTicketCookies.issue("ticket-value", true);

        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    @DisplayName("삭제 쿠키는 값은 비우고 Max-Age=0, 같은 이름·경로·속성")
    void clearExpiresCookieImmediately() {
        ResponseCookie cookie = ConsentTicketCookies.clear(false);

        assertThat(cookie.getName()).isEqualTo("wc_oauth_ticket");
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/oauth2/authorize");
    }

    @Test
    @DisplayName("삭제 쿠키도 HTTPS 요청이면 Secure 플래그를 켠다")
    void clearSetsSecureWhenRequestIsHttps() {
        assertThat(ConsentTicketCookies.clear(true).isSecure()).isTrue();
    }
}
