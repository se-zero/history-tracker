package com.history.pipeline_worker.source.linear;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Jira/GitHub/Slack RateLimiter는 이 레포에 전용 테스트가 없어(JiraRawServiceTest 등에서
// new JiraRateLimiter(0)으로 무력화해 간접 사용) 정확히 미러할 선례가 없다. Linear는 5,000 req/h
// 한도를 Jira처럼 고정 딜레이(app.rate-limit.linear.delay-ms)로 대응하므로, 그 대기 자체를
// 최소한으로 직접 검증한다.
class LinearRateLimiterTest {

    @Test
    void acquire_sleepsForConfiguredDelay() {
        LinearRateLimiter rateLimiter = new LinearRateLimiter(50);

        long start = System.currentTimeMillis();
        rateLimiter.acquire();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(50);
    }

    @Test
    void acquire_zeroDelay_doesNotBlock() {
        LinearRateLimiter rateLimiter = new LinearRateLimiter(0);

        long start = System.currentTimeMillis();
        rateLimiter.acquire();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(50);
    }
}
