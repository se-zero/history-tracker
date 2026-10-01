package com.history.backend.mcp.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.history.backend.mcp.McpRateLimitProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// 사용자별 60초 슬라이딩 윈도우 호출 상한. 에이전트는 사람과 달리 루프에서 질의를 연속 호출할 수 있어
// 월 한도(FREE 10회)가 순식간에 소진되고 ai-engine에 부하가 몰리는 것을 막는다.
// 인스턴스 로컬이라 재시작하면 리셋되고, 인스턴스가 여러 대면 인스턴스별로 적용된다.
// 호출한 적 있는 사용자마다 항목이 하나씩 남는다(사용자당 최대 perMinute개의 시각) — 따로 비우지 않는다.
@Component
public class McpRateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final McpRateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, ArrayDeque<Instant>> windows = new ConcurrentHashMap<>();

    @Autowired
    public McpRateLimiter(McpRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    McpRateLimiter(McpRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    // 허용이면 이번 호출을 기록하고 ZERO, 상한이면 기록 없이 가장 오래된 건이 빠질 때까지의 남은 시간
    public Duration acquire(UUID userId) {
        Instant now = clock.instant();
        Duration[] wait = {Duration.ZERO};
        windows.compute(userId, (id, calls) -> {
            ArrayDeque<Instant> window = calls == null ? new ArrayDeque<>() : calls;
            // 정확히 60초 지난 건은 윈도우에서 빠진다
            while (!window.isEmpty() && !window.peekFirst().plus(WINDOW).isAfter(now)) {
                window.pollFirst();
            }
            if (window.size() < properties.perMinute()) {
                window.addLast(now);
            } else {
                wait[0] = Duration.between(now, window.peekFirst().plus(WINDOW));
            }
            return window;
        });
        return wait[0];
    }
}
