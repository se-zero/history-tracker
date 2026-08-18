package com.history.backend.integration.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.history.backend.integration.domain.IntegrationProvider;
import org.springframework.stereotype.Component;

// provider → IntegrationSelectionFlow 조회. 선택 단계가 없는 provider는 비어 있다.
@Component
public class IntegrationSelectionFlowRegistry {

    private final Map<IntegrationProvider, IntegrationSelectionFlow> flows;

    public IntegrationSelectionFlowRegistry(List<IntegrationSelectionFlow> flows) {
        Map<IntegrationProvider, IntegrationSelectionFlow> byProvider = new EnumMap<>(IntegrationProvider.class);
        for (IntegrationSelectionFlow flow : flows) {
            IntegrationSelectionFlow duplicate = byProvider.put(flow.provider(), flow);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate IntegrationSelectionFlow for provider: " + flow.provider());
            }
        }
        this.flows = Collections.unmodifiableMap(byProvider);
    }

    public Optional<IntegrationSelectionFlow> find(IntegrationProvider provider) {
        return Optional.ofNullable(flows.get(provider));
    }
}
