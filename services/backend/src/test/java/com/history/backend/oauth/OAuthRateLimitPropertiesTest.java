package com.history.backend.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("OAuthRateLimitProperties: 인가 서버 IP별 분당 상한 설정값 검증")
class OAuthRateLimitPropertiesTest {

    @Test
    @DisplayName("1 이상이면 그대로 받는다")
    void acceptsPositiveLimit() {
        assertThat(new OAuthRateLimitProperties(1, null).perMinute()).isEqualTo(1);
        assertThat(new OAuthRateLimitProperties(30, null).perMinute()).isEqualTo(30);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("0 이하는 거부한다")
    void rejectsNonPositiveLimit(int perMinute) {
        assertThatThrownBy(() -> new OAuthRateLimitProperties(perMinute, "CF-Connecting-IP"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("clientIpHeader는 null이어도 빈 값이어도 허용한다")
    void acceptsNullOrBlankClientIpHeader() {
        assertThat(new OAuthRateLimitProperties(5, null).clientIpHeader()).isNull();
        assertThat(new OAuthRateLimitProperties(5, "").clientIpHeader()).isEmpty();
    }

    @Test
    @DisplayName("clientIpHeader 값은 그대로 보존한다")
    void keepsClientIpHeader() {
        assertThat(new OAuthRateLimitProperties(5, "CF-Connecting-IP").clientIpHeader())
                .isEqualTo("CF-Connecting-IP");
    }
}
