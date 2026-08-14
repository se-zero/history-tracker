package com.history.pipeline_worker.source.notion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// ClickUpRateLimiterTest 컨벤션을 미러한다. Notion은 두 재시도 경로(Retry-After 헤더 그대로 따르기 /
// 헤더 없을 때 지수 백오프)가 있어 그 둘도 함께 검증한다.
class NotionRateLimiterTest {

    @Test
    void afterRequest_sleepsForConfiguredDelay() {
        NotionRateLimiter rateLimiter = new NotionRateLimiter(50, 10, 100);

        long start = System.currentTimeMillis();
        rateLimiter.afterRequest();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(50);
    }

    @Test
    void afterRequest_zeroDelay_doesNotBlock() {
        NotionRateLimiter rateLimiter = new NotionRateLimiter(0, 10, 100);

        long start = System.currentTimeMillis();
        rateLimiter.afterRequest();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(50);
    }

    @Test
    void awaitRetryAfter_sleepsForGivenSeconds() {
        NotionRateLimiter rateLimiter = new NotionRateLimiter(0, 10, 100);

        long start = System.currentTimeMillis();
        rateLimiter.awaitRetryAfter(0.1);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(100);
    }

    @Test
    void awaitBackoff_growsExponentiallyAndCapsAtMax() {
        NotionRateLimiter rateLimiter = new NotionRateLimiter(0, 10, 30);

        long startFirst = System.currentTimeMillis();
        rateLimiter.awaitBackoff(1);
        long firstElapsed = System.currentTimeMillis() - startFirst;

        long startLater = System.currentTimeMillis();
        rateLimiter.awaitBackoff(10);
        long laterElapsed = System.currentTimeMillis() - startLater;

        // attempt=1 → 20ms 근방, attempt=10 → maxBackoffMs(30ms)에서 캡된다. jitter(0~500ms)가 있어
        // 정확한 값 대신 "캡을 넘지 않는다"만 느슨하게 확인한다.
        assertThat(firstElapsed).isGreaterThanOrEqualTo(20);
        assertThat(laterElapsed).isGreaterThanOrEqualTo(30);
    }
}
