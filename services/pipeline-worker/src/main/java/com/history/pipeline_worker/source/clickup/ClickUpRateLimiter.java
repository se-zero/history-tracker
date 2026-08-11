package com.history.pipeline_worker.source.clickup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClickUpRateLimiter {

    private final long delayMs;

    // 무료 워크스페이스 100 req/min = 600ms.
    public ClickUpRateLimiter(@Value("${app.rate-limit.clickup.delay-ms:600}") long delayMs) {
        this.delayMs = delayMs;
    }

    /** ClickUp API 호출 후 대기 (무료 워크스페이스 100 req/min 한도 대응) */
    public void acquire() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("ClickUpRateLimiter sleep 중단", e);
        }
    }
}
