package com.history.backend.integration.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.history.backend.integration.domain.IntegrationProvider;
import org.springframework.stereotype.Component;

/**
 * provider → {@link OAuthConnectFlow} 조회.
 *
 * <p>{@link ProviderCredentialLifecycleRegistry}와 분리해 둔다 — connect 흐름은 IntegrationService에
 * 의존하고 IntegrationService는 자격증명 수명주기 레지스트리에 의존하므로, 한 레지스트리에 둘을 함께
 * 담으면 빈 순환 의존이 된다.</p>
 */
@Component
public class OAuthConnectFlowRegistry {

    private final Map<IntegrationProvider, OAuthConnectFlow> flows;

    public OAuthConnectFlowRegistry(List<OAuthConnectFlow> flows) {
        Map<IntegrationProvider, OAuthConnectFlow> byProvider = new EnumMap<>(IntegrationProvider.class);
        for (OAuthConnectFlow flow : flows) {
            OAuthConnectFlow duplicate = byProvider.put(flow.provider(), flow);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate OAuthConnectFlow for provider: " + flow.provider());
            }
        }
        this.flows = Collections.unmodifiableMap(byProvider);
    }

    // OAuth 동의 흐름이 없는 provider(예: App 설치로 붙이는 GitHub)는 비어 있다.
    public Optional<OAuthConnectFlow> find(IntegrationProvider provider) {
        return Optional.ofNullable(flows.get(provider));
    }
}
