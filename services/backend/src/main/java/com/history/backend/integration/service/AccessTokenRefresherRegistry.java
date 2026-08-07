package com.history.backend.integration.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.history.backend.integration.domain.IntegrationProvider;
import org.springframework.stereotype.Component;

// provider → AccessTokenRefresher 조회. 등록 자체가 "갱신을 지원한다"는 선언이라 no-op 폴백을 두지 않는다
// — 갱신이 필요 없는 provider와 필요한데 빠뜨린 provider를 호출부가 구분할 수 있어야 한다.
@Component
public class AccessTokenRefresherRegistry {

    private final Map<IntegrationProvider, AccessTokenRefresher> refreshers;

    public AccessTokenRefresherRegistry(List<AccessTokenRefresher> refreshers) {
        Map<IntegrationProvider, AccessTokenRefresher> byProvider = new EnumMap<>(IntegrationProvider.class);
        for (AccessTokenRefresher refresher : refreshers) {
            AccessTokenRefresher duplicate = byProvider.put(refresher.provider(), refresher);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate AccessTokenRefresher for provider: " + refresher.provider());
            }
        }
        this.refreshers = Collections.unmodifiableMap(byProvider);
    }

    // 비만료 토큰을 쓰는 provider(Slack 등)와 자격증명이 없는 provider(GitHub)는 비어 있다
    public Optional<AccessTokenRefresher> find(IntegrationProvider provider) {
        return Optional.ofNullable(refreshers.get(provider));
    }
}
