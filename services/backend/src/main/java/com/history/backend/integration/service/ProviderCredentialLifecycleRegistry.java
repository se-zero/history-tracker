package com.history.backend.integration.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.history.backend.integration.domain.IntegrationProvider;
import org.springframework.stereotype.Component;

// provider → ProviderCredentialLifecycle 조회. 등록이 없는 provider는 no-op으로 응답한다.
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

    /**
     * 등록된 수명주기, 없으면 no-op.
     *
     * <p>폐기·갱신 대상이 둘 다 없는 provider(GitHub — App 설치는 계정 단위라 유지하고 installation
     * token은 1시간 캐시라 방치해도 만료된다)는 빈을 만들지 않는다. 인터페이스의 유일한 추상 메서드가
     * {@code provider()}라 no-op은 람다로 충분하다.</p>
     */
    public ProviderCredentialLifecycle get(IntegrationProvider provider) {
        return lifecycles.getOrDefault(provider, () -> provider);
    }

    // 등록 여부 자체가 의미 있는 호출부용 — 예를 들어 토큰 갱신 내부 API는 갱신 수단이 없는 provider를
    // 조용히 204로 넘기지 않고 404로 알린다.
    public Optional<ProviderCredentialLifecycle> find(IntegrationProvider provider) {
        return Optional.ofNullable(lifecycles.get(provider));
    }
}
