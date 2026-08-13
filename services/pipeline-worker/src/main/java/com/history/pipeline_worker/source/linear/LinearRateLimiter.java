package com.history.pipeline_worker.source.linear;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LinearRateLimiter {

    private final long delayMs;

    public LinearRateLimiter(@Value("${app.rate-limit.linear.delay-ms:720}") long delayMs) {
        this.delayMs = delayMs;
    }

    /** Linear API 호출 후 대기 (5,000 req/h 한도 대응) */
    public void acquire() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LinearRateLimiter sleep 중단", e);
        }
    }
}
