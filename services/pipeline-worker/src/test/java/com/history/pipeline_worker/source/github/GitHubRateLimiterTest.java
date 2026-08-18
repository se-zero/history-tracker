package com.history.pipeline_worker.source.github;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GitHubRateLimiterTest {

    // tier-3 페이스 계산(잔여 시간 / remaining)을 결정적으로 만들기 위한 고정 시각.
    // tier-2(reset 대기)는 기존 로직대로 실제 시각(System.currentTimeMillis())을 기준으로 하므로
    // 이 Clock에 의존하지 않는다.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("remaining이 pacing 임계값을 초과하면 대기 없이 즉시 반환한다")
    void acquire_remainingAbovePacingThreshold_returnsImmediately() {
        GitHubRateLimiter rateLimiter = new GitHubRateLimiter(300, 10, 500);
        HttpHeaders headers = rateLimitHeaders("4000", String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond()));

        long start = System.currentTimeMillis();
        rateLimiter.acquire(headers);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(150);
    }

    @Test
    @DisplayName("remaining이 저잔량·pacing 임계값 사이면 잔여 시간을 remaining으로 나눈 페이스만큼 대기한다")
    void acquire_remainingBetweenLowAndPacingThreshold_sleepsProportionalPace() {
        // reset까지 2초, remaining=20 → 기대 페이스 sleep = 2000ms / 20 = 100ms
        GitHubRateLimiter rateLimiter = new GitHubRateLimiter(1000, 10, 500, CLOCK);
        HttpHeaders headers = rateLimitHeaders("20", String.valueOf(CLOCK.instant().plusSeconds(2).getEpochSecond()));

        long start = System.currentTimeMillis();
        rateLimiter.acquire(headers);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(80).isLessThan(400);
    }

    @Test
    @DisplayName("remaining이 저잔량 임계값 이하면 reset 시각까지 대기하고 500ms 버퍼를 더한다")
    void acquire_remainingAtOrBelowLowThreshold_sleepsUntilResetPlusBuffer() {
        GitHubRateLimiter rateLimiter = new GitHubRateLimiter(300, 10, 500);
        // 초 단위 절삭으로 인한 결정 불가를 피하려고 reset을 현재 epoch 초보다 1초 뒤로 잡는다 —
        // resetEpoch*1000 - now는 항상 1~1000ms 사이가 되어 500ms 버퍼를 더하면 항상 500ms를 넘는다.
        long resetEpochSecond = (System.currentTimeMillis() / 1000) + 1;
        HttpHeaders headers = rateLimitHeaders("5", String.valueOf(resetEpochSecond));

        long start = System.currentTimeMillis();
        rateLimiter.acquire(headers);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("headers가 null이면 defaultDelayMs만큼 폴백 대기한다")
    void acquire_nullHeaders_sleepsDefaultDelay() {
        GitHubRateLimiter rateLimiter = new GitHubRateLimiter(200, 10, 500);

        long start = System.currentTimeMillis();
        rateLimiter.acquire(null);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(150);
    }

    @Test
    @DisplayName("remaining이 숫자로 파싱되지 않으면 예외 없이 defaultDelayMs로 폴백한다")
    void acquire_nonNumericRemaining_fallsBackWithoutException() {
        GitHubRateLimiter rateLimiter = new GitHubRateLimiter(200, 10, 500);
        HttpHeaders headers = rateLimitHeaders("abc", null);

        long start = System.currentTimeMillis();
        assertThatCode(() -> rateLimiter.acquire(headers)).doesNotThrowAnyException();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(150);
    }

    @Test
    @DisplayName("remaining은 정상이지만 reset이 숫자로 파싱되지 않으면 defaultDelayMs로 폴백한다")
    void acquire_nonNumericReset_fallsBackToDefaultDelay() {
        // remaining=20은 tier-3(저잔량·pacing 임계값 사이) 구간 값 — reset 파싱 실패가 원인임을 명확히 한다.
        GitHubRateLimiter rateLimiter = new GitHubRateLimiter(200, 10, 500);
        HttpHeaders headers = rateLimitHeaders("20", "xyz");

        long start = System.currentTimeMillis();
        rateLimiter.acquire(headers);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(150);
    }

    private HttpHeaders rateLimitHeaders(String remaining, String reset) {
        HttpHeaders headers = new HttpHeaders();
        if (remaining != null) headers.set("X-RateLimit-Remaining", remaining);
        if (reset != null) headers.set("X-RateLimit-Reset", reset);
        return headers;
    }
}
