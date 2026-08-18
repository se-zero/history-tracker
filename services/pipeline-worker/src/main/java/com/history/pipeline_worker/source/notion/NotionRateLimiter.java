package com.history.pipeline_worker.source.notion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotionRateLimiter {

    // 연결(우리 앱)당 평균 3 req/s — 350ms면 그 아래로 여유 있게 유지된다.
    private final long defaultDelayMs;
    // Retry-After 헤더가 없을 때만 쓰는 지수 백오프 초기값·상한. 생성자로 뺀 이유는 테스트에서
    // 실제 초 단위로 대기하지 않도록 작은 값을 주입하기 위함이다.
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    @Autowired
    public NotionRateLimiter(
            @Value("${app.rate-limit.notion.default-delay-ms:350}") long defaultDelayMs) {
        this(defaultDelayMs, 1000, 30_000);
    }

    NotionRateLimiter(long defaultDelayMs, long initialBackoffMs, long maxBackoffMs) {
        this.defaultDelayMs = defaultDelayMs;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    /** 호출마다 고정 딜레이. */
    public void afterRequest() {
        sleep(defaultDelayMs);
    }

    /**
     * 429·529 응답의 Retry-After 헤더(초)를 그대로 따른다. Google Chat과 달리 Notion은 서버가
     * 대기 시간을 알려주므로 헤더가 있으면 백오프보다 우선한다.
     */
    public void awaitRetryAfter(double retryAfterSeconds) {
        sleep(Math.round(retryAfterSeconds * 1000));
    }

    /** Retry-After 헤더가 없을 때만 쓰는 지수 백오프({@code min((2^n)+jitter, max)}). */
    public void awaitBackoff(int attempt) {
        long backoff = Math.min(initialBackoffMs * (1L << Math.min(attempt, 10)), maxBackoffMs);
        long jitter = Math.round(Math.random() * 500);
        sleep(backoff + jitter);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("NotionRateLimiter sleep 중단", e);
        }
    }
}
