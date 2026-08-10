package com.history.pipeline_worker.source.asana;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Asana도 Jira/Linear처럼 호출당 고정 딜레이(app.rate-limit.asana.delay-ms)로 API 호출 한도에
// 대응한다. LinearRateLimiterTest 컨벤션을 그대로 미러한다.
class AsanaRateLimiterTest {

    @Test
    void acquire_sleepsForConfiguredDelay() {
        AsanaRateLimiter rateLimiter = new AsanaRateLimiter(50);

        long start = System.currentTimeMillis();
        rateLimiter.acquire();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(50);
    }

    @Test
    void acquire_zeroDelay_doesNotBlock() {
        AsanaRateLimiter rateLimiter = new AsanaRateLimiter(0);

        long start = System.currentTimeMillis();
        rateLimiter.acquire();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(50);
    }
}
