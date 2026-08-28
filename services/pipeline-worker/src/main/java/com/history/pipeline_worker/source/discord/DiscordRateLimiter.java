package com.history.pipeline_worker.source.discord;

import com.history.pipeline_worker.collection.ProjectFairGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DiscordRateLimiter {

    // 봇 토큰 하나를 앱 전체 프로젝트가 공유한다 — 프로젝트별 라운드로빈으로 순번을 배정해 큰
    // 길드를 붙인 프로젝트 하나가 이 자원을 독점하지 못하게 한다.
    private final ProjectFairGate fairGate;

    public DiscordRateLimiter(
            @Value("${app.rate-limit.discord.default-delay-ms:250}") long defaultDelayMs) {
        this.fairGate = new ProjectFairGate(defaultDelayMs);
    }

    /** 프로젝트별 공정 큐를 거쳐 페이싱한다 — 봇당 초당 50요청 상한에 여유를 두고 보수적으로 시작한다. */
    public void acquire(String projectId) {
        fairGate.acquire(projectId);
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
