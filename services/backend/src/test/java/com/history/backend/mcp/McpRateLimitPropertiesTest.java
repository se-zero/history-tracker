package com.history.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("McpRateLimitProperties: 분당 상한 설정값 검증")
class McpRateLimitPropertiesTest {

    @Test
    @DisplayName("1 이상이면 그대로 받는다")
    void acceptsPositiveLimit() {
        assertThat(new McpRateLimitProperties(1).perMinute()).isEqualTo(1);
        assertThat(new McpRateLimitProperties(10).perMinute()).isEqualTo(10);
    }

    // 0 이하가 들어가면 McpRateLimiter가 빈 윈도우에서 예외를 내 모든 질의가 원인 모를 오류가 된다.
    // 기동 시점에 설정 오류로 드러나게 한다.
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("0 이하는 거부한다")
    void rejectsNonPositiveLimit(int perMinute) {
        assertThatThrownBy(() -> new McpRateLimitProperties(perMinute))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp.rate-limit.per-minute");
    }
}
