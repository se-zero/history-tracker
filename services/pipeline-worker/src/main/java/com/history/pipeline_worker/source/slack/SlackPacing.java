package com.history.pipeline_worker.source.slack;

import java.util.EnumMap;
import java.util.Map;

/**
 * collect 실행(1회) 동안만 살아있는 429 페이싱 상태 홀더.
 * SlackRateLimiter는 싱글턴 빈이라 여기에 429 이력을 두면 3-스레드 collectionTaskExecutor에서
 * 동시에 도는 서로 다른 프로젝트의 상태가 섞인다 — 그래서 collect 1회 실행 단위로 새로 만들어
 * SlackFetchContext에 담아 격리한다. collect 실행 자체가 스레드 1개에서 동기로 진행되므로
 * 이 안에서는 동기화가 필요 없다(EnumMap으로 충분).
 */
class SlackPacing {

    enum Endpoint { USERS_LIST, CONVERSATIONS_LIST, HISTORY, REPLIES }

    private final Map<Endpoint, Long> minDelayMsByEndpoint = new EnumMap<>(Endpoint.class);

    void escalate(Endpoint endpoint, long delayMs) {
        minDelayMsByEndpoint.merge(endpoint, delayMs, Math::max);
    }

    long minDelayMs(Endpoint endpoint) {
        return minDelayMsByEndpoint.getOrDefault(endpoint, 0L);
    }
}
