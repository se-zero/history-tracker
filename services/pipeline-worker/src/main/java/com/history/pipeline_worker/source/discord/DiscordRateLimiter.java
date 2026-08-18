package com.history.pipeline_worker.source.discord;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DiscordRateLimiter {

    private final long defaultDelayMs;

    public DiscordRateLimiter(
            @Value("${app.rate-limit.discord.default-delay-ms:250}") long defaultDelayMs) {
        this.defaultDelayMs = defaultDelayMs;
    }

    /** 호출마다 고정 딜레이 — 봇당 초당 50요청 상한에 여유를 두고 보수적으로 시작한다. */
    public void afterRequest() {
        sleep(defaultDelayMs);
    }

    /** 429 응답의 retry_after(초, 소수)만큼 대기 */
    public void awaitRetry(double retryAfterSeconds) {
        sleep(Math.round(retryAfterSeconds * 1000));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DiscordRateLimiter sleep 중단", e);
        }
    }
}
