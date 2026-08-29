package com.history.pipeline_worker.source.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DiscordRateLimiter: 호출마다 고정 딜레이를 두던 afterRequest()가 프로젝트별 공정 큐를 거치는
 * acquire(projectId)로 바뀌었다 — 내부적으로 ProjectFairGate에 위임한다. awaitRetry(429 백오프)는
 * 그대로 유지된다.
 */
class DiscordRateLimiterTest {

    @Test
    @DisplayName("acquire(projectId)는 ProjectFairGate를 거쳐 페이싱된다 — 같은 projectId의 연속 호출은 delay 이상 간격을 둔다")
    void acquire_sameProject_pacesAtConfiguredDelay() {
        DiscordRateLimiter rateLimiter = new DiscordRateLimiter(20);

        rateLimiter.acquire("project-1");
        long start = System.currentTimeMillis();
        rateLimiter.acquire("project-1");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(20);
    }

    @Test
    @DisplayName("delay=0으로 생성하면 acquire가 즉시 반환된다 — 기존 rate limiter 무력화 관례가 새 시그니처에서도 유효하다")
    void acquire_zeroDelay_doesNotBlock() {
        DiscordRateLimiter rateLimiter = new DiscordRateLimiter(0);

        long start = System.currentTimeMillis();
        rateLimiter.acquire("project-1");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(50);
    }

    @Test
    @DisplayName("awaitRetry는 retry_after(초)만큼 대기한다 — 429 백오프 동작은 시그니처·동작 그대로 유지된다")
    void awaitRetry_sleepsForRetryAfterSeconds() {
        DiscordRateLimiter rateLimiter = new DiscordRateLimiter(0);

        long start = System.currentTimeMillis();
        rateLimiter.awaitRetry(0.03);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(30);
    }
}
