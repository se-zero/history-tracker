package com.history.backend.integration.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.history.backend.integration.domain.IntegrationProvider;
import org.springframework.stereotype.Component;

// provider → OAuthConnectFlow 조회 (SPI마다 레지스트리를 하나씩 둔다)
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
