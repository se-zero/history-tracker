package com.history.backend.integration.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.history.backend.integration.domain.IntegrationProvider;
import org.springframework.stereotype.Component;

// provider → ProviderCredentialLifecycle 조회. 등록 자체가 "폐기를 지원한다"는 선언이라 no-op 폴백을
// 두지 않는다 — AccessTokenRefresherRegistry와 같은 이유로, 폐기 대상이 없는 provider와 필요한데
// 빠뜨린 provider를 호출부가 구분할 수 있어야 한다.
@Component
public class ProviderCredentialLifecycleRegistry {

    private final Map<IntegrationProvider, ProviderCredentialLifecycle> lifecycles;

    public ProviderCredentialLifecycleRegistry(List<ProviderCredentialLifecycle> lifecycles) {
        Map<IntegrationProvider, ProviderCredentialLifecycle> byProvider = new EnumMap<>(IntegrationProvider.class);
        for (ProviderCredentialLifecycle lifecycle : lifecycles) {
            ProviderCredentialLifecycle duplicate = byProvider.put(lifecycle.provider(), lifecycle);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate ProviderCredentialLifecycle for provider: " + lifecycle.provider());
            }
        }
        this.lifecycles = Collections.unmodifiableMap(byProvider);
    }

    // 폐기 대상이 없는 provider(GitHub — App 설치는 계정 단위라 유지하고 installation token은
    // 1시간 캐시라 방치해도 만료된다)는 빈을 만들지 않는다
    public Optional<ProviderCredentialLifecycle> find(IntegrationProvider provider) {
        return Optional.ofNullable(lifecycles.get(provider));
    }
}
