package com.history.pipeline_worker.source.googlechat;

import com.history.pipeline_worker.collection.ProjectFairGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GoogleChatRateLimiter {

    // Cloud 프로젝트당 60초에 3,000요청(스페이스·메시지 읽기 쿼터 공용) — 이 쿼터는 우리 앱을 쓰는
    // 모든 사용자가 공유하므로 사용자 수만큼 늘지 않는다. 그 쿼터를 프로젝트별 라운드로빈으로 순번을
    // 배정해 큰 스페이스를 붙인 프로젝트 하나가 독점하지 못하게 한다. 초기값은 보수적으로 짧게 잡는다.
    private final ProjectFairGate fairGate;
    // 생성자로 뺀 이유: 테스트에서 실제 초 단위로 대기하지 않도록 작은 값을 주입하기 위함이다
    // (retry_after를 서버가 안 주는 Google 429는 Discord와 달리 응답값으로 대기 시간을 좁힐 수 없다).
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    @Autowired
    public GoogleChatRateLimiter(
            @Value("${app.rate-limit.google-chat.default-delay-ms:100}") long defaultDelayMs) {
        this(defaultDelayMs, 1000, 30_000);
    }

    GoogleChatRateLimiter(long defaultDelayMs, long initialBackoffMs, long maxBackoffMs) {
        this.fairGate = new ProjectFairGate(defaultDelayMs);
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    /** 프로젝트별 공정 큐를 거쳐 페이싱한다. */
    public void acquire(String projectId) {
        fairGate.acquire(projectId);
    }

    /**
     * 429 응답에는 Discord처럼 재시도 대기 시간을 알려주는 필드가 없다 — 문서 권고대로 지수 백오프
     * ({@code min((2^n)+jitter, max)})로 대기한다.
     */
    public void awaitRetry(int attempt) {
        long backoff = Math.min(initialBackoffMs * (1L << Math.min(attempt, 10)), maxBackoffMs);
        long jitter = Math.round(Math.random() * 500);
        sleep(backoff + jitter);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("GoogleChatRateLimiter sleep 중단", e);
        }
    }
}
