package com.history.backend.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import com.history.backend.mcp.McpRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("McpRateLimiter: 사용자별 60초 슬라이딩 윈도우 상한")
class McpRateLimiterTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID OTHER_USER_ID = UUID.fromString("0f80f8ae-3fb1-4d90-978e-579a890e9478");
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);

    @Test
    @DisplayName("상한 미만이면 허용(ZERO)하고 상한에 닿으면 양수 대기 시간을 반환")
    void allowsUpToLimitThenRejectsWithPositiveWait() {
        McpRateLimiter limiter = limiter(10);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.acquire(USER_ID)).isEqualTo(Duration.ZERO);
        }

        assertThat(limiter.acquire(USER_ID)).isPositive();
    }

    @Test
    @DisplayName("거부된 호출은 기록되지 않는다 — 가장 오래된 건이 빠지면 정확히 1건만 다시 허용")
    void rejectedCallIsNotRecorded() {
        McpRateLimiter limiter = limiter(10);
        limiter.acquire(USER_ID);                       // t=0
        clock.advance(Duration.ofSeconds(10));
        for (int i = 0; i < 9; i++) {
            limiter.acquire(USER_ID);                   // t=10, 합계 10건
        }
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.acquire(USER_ID)).isPositive();   // t=20 거부
        assertThat(limiter.acquire(USER_ID)).isPositive();   // 거부가 또 있어도 기록 없음

        clock.set(START.plusSeconds(60));               // t=0 건만 윈도우에서 빠진다

        assertThat(limiter.acquire(USER_ID)).isEqualTo(Duration.ZERO);
        assertThat(limiter.acquire(USER_ID)).isPositive();
    }

    @Test
    @DisplayName("가장 오래된 건이 60초를 지나면 다시 허용")
    void allowsAgainOnceOldestCallLeavesWindow() {
        McpRateLimiter limiter = limiter(10);
        for (int i = 0; i < 10; i++) {
            limiter.acquire(USER_ID);
        }
        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.acquire(USER_ID)).isPositive();

        clock.advance(Duration.ofSeconds(1));

        assertThat(limiter.acquire(USER_ID)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("대기 시간은 가장 오래된 건이 윈도우에서 빠질 때까지 남은 시간")
    void waitIsTimeUntilOldestCallLeavesWindow() {
        McpRateLimiter limiter = limiter(10);
        for (int i = 0; i < 10; i++) {
            limiter.acquire(USER_ID);
        }
        clock.advance(Duration.ofSeconds(20));

        assertThat(limiter.acquire(USER_ID)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("사용자별로 독립 — 한 사용자의 소진이 다른 사용자를 막지 않는다")
    void usersAreIndependent() {
        McpRateLimiter limiter = limiter(10);
        for (int i = 0; i < 10; i++) {
            limiter.acquire(USER_ID);
        }

        assertThat(limiter.acquire(USER_ID)).isPositive();
        assertThat(limiter.acquire(OTHER_USER_ID)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("perMinute 설정값을 따른다")
    void respectsConfiguredLimit() {
        McpRateLimiter limiter = limiter(2);

        assertThat(limiter.acquire(USER_ID)).isEqualTo(Duration.ZERO);
        assertThat(limiter.acquire(USER_ID)).isEqualTo(Duration.ZERO);
        assertThat(limiter.acquire(USER_ID)).isPositive();
    }

    private McpRateLimiter limiter(int perMinute) {
        return new McpRateLimiter(new McpRateLimitProperties(perMinute), clock);
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
