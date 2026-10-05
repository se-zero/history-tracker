package com.history.backend.oauth.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.history.backend.oauth.OAuthRateLimitProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 로그인 없이 부를 수 있는 인가 서버 주소(CIMD 문서 조회·클라이언트 행 생성을 일으킨다)의 키(IP)별 60초 슬라이딩 윈도우 상한.
// 인스턴스 로컬이라 재시작하면 리셋되고, 인스턴스가 여러 대면 인스턴스별로 적용된다.
// IP는 사용자와 달리 끝없이 늘 수 있고 개인정보라, 창 안에 호출이 없는 키를 1분 주기로 지운다.
@Component
public class OAuthRateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final OAuthRateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, ArrayDeque<Instant>> windows = new ConcurrentHashMap<>();

    @Autowired
    public OAuthRateLimiter(OAuthRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    OAuthRateLimiter(OAuthRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    // 허용이면 이번 호출을 기록하고 ZERO, 상한이면 기록 없이 가장 오래된 건이 빠질 때까지의 남은 시간
    public Duration acquire(String key) {
        Instant now = clock.instant();
        Duration[] wait = {Duration.ZERO};
        windows.compute(key, (k, calls) -> {
            ArrayDeque<Instant> window = calls == null ? new ArrayDeque<>() : calls;
            evictExpired(window, now);
            if (window.size() < properties.perMinute()) {
                window.addLast(now);
            } else {
                wait[0] = Duration.between(now, window.peekFirst().plus(WINDOW));
            }
            return window;
        });
        return wait[0];
    }

    int trackedKeyCount() {
        return windows.size();
    }

    // 개인정보처리방침 제5조가 "마지막 요청으로부터 늦어도 약 2분 안에 메모리에서 지운다"고 적고 있다(창 60초 + 이 주기 60초).
    // 요청이 올 때만 청소하면 한가한 시간대에는 IP가 메모리에 남으므로 요청과 무관하게 돈다. 주기를 바꾸면 방침 문구도 고친다.
    // 덱은 키별 computeIfPresent 안에서만 만진다 — 밖에서 순회하면 acquire와 경합한다.
    @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void evictIdleKeys() {
        Instant now = clock.instant();
        for (String key : windows.keySet()) {
            windows.computeIfPresent(key, (k, window) -> {
                evictExpired(window, now);
                return window.isEmpty() ? null : window;
            });
        }
    }

    // 정확히 60초 지난 건은 윈도우에서 빠진다
    private static void evictExpired(ArrayDeque<Instant> window, Instant now) {
        while (!window.isEmpty() && !window.peekFirst().plus(WINDOW).isAfter(now)) {
            window.pollFirst();
        }
    }
}
