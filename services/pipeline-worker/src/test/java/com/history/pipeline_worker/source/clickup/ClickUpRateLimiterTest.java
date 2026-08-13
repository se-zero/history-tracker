package com.history.pipeline_worker.source.clickup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// ClickUp도 Asana처럼 호출당 고정 딜레이(app.rate-limit.clickup.delay-ms)로 API 호출 한도(무료 워크스페이스
// 100 req/min)에 대응한다. AsanaRateLimiterTest 컨벤션을 그대로 미러한다.
class ClickUpRateLimiterTest {

    @Test
    void acquire_sleepsForConfiguredDelay() {
        ClickUpRateLimiter rateLimiter = new ClickUpRateLimiter(50);

        long start = System.currentTimeMillis();
        rateLimiter.acquire();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(50);
    }

    @Test
    void acquire_zeroDelay_doesNotBlock() {
        ClickUpRateLimiter rateLimiter = new ClickUpRateLimiter(0);

        long start = System.currentTimeMillis();
        rateLimiter.acquire();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(50);
    }
}
