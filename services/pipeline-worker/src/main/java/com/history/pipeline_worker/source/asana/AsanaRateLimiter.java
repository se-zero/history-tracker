package com.history.pipeline_worker.source.asana;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AsanaRateLimiter {

    private final long delayMs;

    // 무료 워크스페이스 150 req/min = 400ms. 유료(1500/min)에선 보수적이지만 요금제를 알 수 없어
    // 무료 한도에 정합한다.
    public AsanaRateLimiter(@Value("${app.rate-limit.asana.delay-ms:400}") long delayMs) {
        this.delayMs = delayMs;
    }

    /** Asana API 호출 후 대기 (무료 워크스페이스 150 req/min 한도 대응) */
    public void acquire() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("AsanaRateLimiter sleep 중단", e);
        }
    }
}
