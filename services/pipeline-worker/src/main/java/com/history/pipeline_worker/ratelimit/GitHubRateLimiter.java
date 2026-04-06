package com.history.pipeline_worker.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GitHubRateLimiter {

    private final int defaultDelayMs;
    private final int lowRemainingThreshold;

    public GitHubRateLimiter(
            @Value("${app.rate-limit.github.default-delay-ms:300}") int defaultDelayMs,
            @Value("${app.rate-limit.github.low-remaining-threshold:10}") int lowRemainingThreshold) {
        this.defaultDelayMs = defaultDelayMs;
        this.lowRemainingThreshold = lowRemainingThreshold;
    }

    /**
     * API 호출 후 응답 헤더를 기반으로 대기.
     * X-RateLimit-Remaining이 임계값 이하이면 X-RateLimit-Reset 시각까지 sleep.
     * headers가 null이거나 헤더가 없으면 defaultDelayMs만큼 sleep.
     */
    public void acquire(HttpHeaders headers) {
        if (headers != null) {
            String remainingStr = headers.getFirst("X-RateLimit-Remaining");
            String resetStr = headers.getFirst("X-RateLimit-Reset");

            if (remainingStr != null) {
                int remaining = Integer.parseInt(remainingStr);
                if (remaining <= lowRemainingThreshold && resetStr != null) {
                    long resetEpoch = Long.parseLong(resetStr);
                    long sleepMs = (resetEpoch * 1000L) - System.currentTimeMillis() + 500L;
                    if (sleepMs > 0) {
                        log.warn("GitHub rate limit 임박 (remaining={}), {}ms 대기", remaining, sleepMs);
                        sleep(sleepMs);
                    }
                    return;
                }
            }
        }
        sleep(defaultDelayMs);
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
