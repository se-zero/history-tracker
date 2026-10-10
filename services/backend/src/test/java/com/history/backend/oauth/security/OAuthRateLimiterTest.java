package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.history.backend.oauth.OAuthRateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("OAuthRateLimiter: 키(IP)별 60초 슬라이딩 윈도우 상한")
class OAuthRateLimiterTest {

    private static final String KEY = "203.0.113.1";
    private static final String OTHER_KEY = "203.0.113.2";
    private static final Instant START = Instant.parse("2026-10-05T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);

    @Test
    @DisplayName("상한까지는 허용(ZERO)하고 상한+1번째는 양수 대기 시간을 반환")
    void allowsUpToLimitThenRejectsWithPositiveWait() {
        OAuthRateLimiter limiter = limiter(3);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.acquire(KEY)).isEqualTo(Duration.ZERO);
        }

        assertThat(limiter.acquire(KEY)).isPositive();
    }

    @Test
    @DisplayName("대기 시간은 가장 오래된 호출이 윈도우에서 빠질 때까지 남은 시간")
    void waitIsTimeUntilOldestCallLeavesWindow() {
        OAuthRateLimiter limiter = limiter(3);
        for (int i = 0; i < 3; i++) {
            limiter.acquire(KEY);
        }
        clock.advance(Duration.ofSeconds(20));

        assertThat(limiter.acquire(KEY)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("거부된 호출은 기록되지 않는다 — 거부가 반복돼도 대기 시간이 늘지 않는다")
    void rejectedCallDoesNotExtendWait() {
        OAuthRateLimiter limiter = limiter(1);
        limiter.acquire(KEY);                                // t=0
        clock.advance(Duration.ofSeconds(10));

        assertThat(limiter.acquire(KEY)).isEqualTo(Duration.ofSeconds(50));
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.acquire(KEY)).isEqualTo(Duration.ofSeconds(40));   // t=0 건 기준 그대로

        clock.set(START.plusSeconds(60));

        assertThat(limiter.acquire(KEY)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("거부된 호출은 기록되지 않는다 — 가장 오래된 건이 빠지면 정확히 1건만 다시 허용")
    void rejectedCallIsNotRecorded() {
        OAuthRateLimiter limiter = limiter(3);
        limiter.acquire(KEY);                                // t=0
        clock.advance(Duration.ofSeconds(10));
        limiter.acquire(KEY);                                // t=10
        limiter.acquire(KEY);                                // t=10, 합계 3건
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.acquire(KEY)).isPositive();       // t=20 거부
        assertThat(limiter.acquire(KEY)).isPositive();       // 거부가 또 있어도 기록 없음

        clock.set(START.plusSeconds(60));                    // t=0 건만 윈도우에서 빠진다

        assertThat(limiter.acquire(KEY)).isEqualTo(Duration.ZERO);
        assertThat(limiter.acquire(KEY)).isPositive();
    }

    @Test
    @DisplayName("윈도우 경계 — 59초에는 거부, 60초에는 다시 허용")
    void allowsAgainExactlyWhenOldestCallLeavesWindow() {
        OAuthRateLimiter limiter = limiter(3);
        for (int i = 0; i < 3; i++) {
            limiter.acquire(KEY);
        }
        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.acquire(KEY)).isEqualTo(Duration.ofSeconds(1));

        clock.advance(Duration.ofSeconds(1));

        assertThat(limiter.acquire(KEY)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("키별로 독립 — 한 키의 소진이 다른 키를 막지 않는다")
    void keysAreIndependent() {
        OAuthRateLimiter limiter = limiter(3);
        for (int i = 0; i < 3; i++) {
            limiter.acquire(KEY);
        }

        assertThat(limiter.acquire(KEY)).isPositive();
        assertThat(limiter.acquire(OTHER_KEY)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("주기 청소 — 창 안에 호출이 하나도 안 남은 키는 다음 요청이 없어도 지워진다")
    void evictIdleKeysRemovesKeysWithNoCallsInWindowWithoutFurtherRequests() {
        OAuthRateLimiter limiter = limiter(3);
        limiter.acquire(KEY);
        limiter.acquire(OTHER_KEY);
        clock.advance(Duration.ofSeconds(60));

        limiter.evictIdleKeys();

        assertThat(limiter.trackedKeyCount()).isZero();
    }

    @Test
    @DisplayName("주기 청소 — 창 안에 호출이 남은 키는 지우지 않고 기록도 그대로 둔다")
    void evictIdleKeysKeepsKeysWithCallsInWindow() {
        OAuthRateLimiter limiter = limiter(3);
        limiter.acquire(KEY);                                // t=0
        clock.advance(Duration.ofSeconds(59));
        limiter.acquire(OTHER_KEY);                          // t=59
        clock.advance(Duration.ofSeconds(1));                // t=60 — KEY의 건은 창에서 빠지고 OTHER_KEY의 건은 남는다

        limiter.evictIdleKeys();

        assertThat(limiter.trackedKeyCount()).isEqualTo(1);
        // 청소가 OTHER_KEY의 기록까지 지웠다면 여기서 상한 전에 다시 허용돼 버린다
        limiter.acquire(OTHER_KEY);
        limiter.acquire(OTHER_KEY);
        assertThat(limiter.acquire(OTHER_KEY)).isPositive();
    }

    @Test
    @DisplayName("주기 청소는 1분마다 돈다 — 개인정보처리방침의 '늦어도 약 2분 안에 삭제'가 이 주기에 기대고 있다")
    void evictIdleKeysIsScheduledEveryMinute() throws Exception {
        Scheduled scheduled = OAuthRateLimiter.class.getDeclaredMethod("evictIdleKeys").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(Duration.of(scheduled.fixedDelay(), scheduled.timeUnit().toChronoUnit()))
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("주기 청소는 공용 풀이 아니라 전용 스케줄러에서 돈다 — 야간 cron이 길어져도 청소가 밀리지 않게")
    void evictIdleKeysRunsOnDedicatedScheduler() throws Exception {
        Scheduled scheduled = OAuthRateLimiter.class.getMethod("evictIdleKeys").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo("oauthRateLimitEvictionScheduler");
    }

    private OAuthRateLimiter limiter(int perMinute) {
        return new OAuthRateLimiter(new OAuthRateLimitProperties(perMinute, "CF-Connecting-IP"), clock);
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
