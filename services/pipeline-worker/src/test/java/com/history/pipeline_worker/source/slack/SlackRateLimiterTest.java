package com.history.pipeline_worker.source.slack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackRateLimiterTest {

    @Test
    @DisplayName("설정 딜레이보다 minDelayMs가 크면 minDelayMs만큼 대기한다")
    void afterConversationsHistory_minDelayLargerThanConfig_sleepsAtLeastMinDelay() {
        SlackRateLimiter rateLimiter = new SlackRateLimiter(0, 0, 0);

        long start = System.currentTimeMillis();
        rateLimiter.afterConversationsHistory(120);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(120);
    }

    @Test
    @DisplayName("minDelayMs보다 설정 딜레이가 크면 설정 딜레이만큼 대기한다")
    void afterConversationsHistory_configLargerThanMinDelay_sleepsAtLeastConfig() {
        SlackRateLimiter rateLimiter = new SlackRateLimiter(0, 120, 0);

        long start = System.currentTimeMillis();
        rateLimiter.afterConversationsHistory(0);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(120);
    }

    @Test
    @DisplayName("awaitRetry는 retryAfterSeconds를 밀리초로 환산해 대기한다")
    void awaitRetry_sleepsForRetryAfterSeconds() {
        SlackRateLimiter rateLimiter = new SlackRateLimiter(0, 0, 0);

        long start = System.currentTimeMillis();
        rateLimiter.awaitRetry(1);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(1000);
    }
}
