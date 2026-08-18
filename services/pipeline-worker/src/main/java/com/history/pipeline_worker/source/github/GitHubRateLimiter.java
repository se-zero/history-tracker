package com.history.pipeline_worker.source.github;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 3단 적응형 GitHub rate limit 대기.
 * 설정값(defaultDelayMs·lowRemainingThreshold·pacingRemainingThreshold)과 clock만 들고 있는
 * 무상태 컴포넌트라 여러 스레드가 동시에 acquire해도 안전하다(후속 병렬화의 전제).
 */
@Slf4j
@Component
public class GitHubRateLimiter {

    private final int defaultDelayMs;
    private final int lowRemainingThreshold;
    private final int pacingRemainingThreshold;
    private final Clock clock;

    @Autowired
    public GitHubRateLimiter(
            @Value("${app.rate-limit.github.default-delay-ms:300}") int defaultDelayMs,
            @Value("${app.rate-limit.github.low-remaining-threshold:10}") int lowRemainingThreshold,
            @Value("${app.rate-limit.github.pacing-remaining-threshold:500}") int pacingRemainingThreshold) {
        this(defaultDelayMs, lowRemainingThreshold, pacingRemainingThreshold, Clock.systemUTC());
    }

    // Clock 주입 생성자 — pacing 구간 계산을 테스트에서 결정적으로 만들기 위한 seam.
    // reset 대기 구간(tier 2)은 기존처럼 clock.millis() 기준이지만 주 생성자가 systemUTC라 실환경에서는
    // System.currentTimeMillis()와 동일하게 동작한다.
    public GitHubRateLimiter(
            int defaultDelayMs,
            int lowRemainingThreshold,
            int pacingRemainingThreshold,
            Clock clock) {
        this.defaultDelayMs = defaultDelayMs;
        this.lowRemainingThreshold = lowRemainingThreshold;
        this.pacingRemainingThreshold = pacingRemainingThreshold;
        this.clock = clock;
    }

    /**
     * API 호출 후 응답 헤더를 기반으로 대기.
     * - remaining이 pacingRemainingThreshold를 초과하면 대기 없이 즉시 반환한다.
     * - remaining이 lowRemainingThreshold와 pacingRemainingThreshold 사이면 reset까지 남은 시간을
     *   remaining으로 나눈 페이스만큼 대기한다(잔여 호출을 reset까지 고르게 소진).
     * - remaining이 lowRemainingThreshold 이하면 X-RateLimit-Reset 시각까지 500ms 버퍼를 더해 대기한다.
     * - headers가 없거나 remaining/reset이 결손·파싱 불가면 defaultDelayMs로 안전 폴백한다.
     */
    public void acquire(HttpHeaders headers) {
        Long remaining = headers != null ? parseLongOrNull(headers.getFirst("X-RateLimit-Remaining")) : null;
        if (remaining == null) {
            sleep(defaultDelayMs);
            return;
        }
        if (remaining > pacingRemainingThreshold) {
            return;
        }

        Long resetEpoch = parseLongOrNull(headers.getFirst("X-RateLimit-Reset"));
        if (resetEpoch == null) {
            sleep(defaultDelayMs);
            return;
        }

        if (remaining <= lowRemainingThreshold) {
            long sleepMs = (resetEpoch * 1000L) - clock.millis() + 500L;
            if (sleepMs > 0) {
                log.warn("GitHub rate limit 임박 (remaining={}), {}ms 대기", remaining, sleepMs);
                sleep(sleepMs);
            }
            return;
        }

        long sleepMs = Math.max(0, (resetEpoch * 1000L - clock.millis()) / Math.max(remaining, 1L));
        sleep(sleepMs);
    }

    /** 403/429 Retry-After(초)만큼 대기 */
    public void awaitRetry(long retryAfterSeconds) {
        sleep(retryAfterSeconds * 1000L);
    }

    private static Long parseLongOrNull(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("GitHubRateLimiter sleep 중단", e);
        }
    }
}
