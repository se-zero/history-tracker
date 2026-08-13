package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.history.backend.integration.domain.IntegrationProvider;
import org.junit.jupiter.api.Test;

// AccessTokenRefresherRegistry·OAuthConnectFlowRegistry·IntegrationSelectionFlowRegistry와 같은
// find(provider) 계약으로 맞춘다 — 등록 여부와 "폐기할 게 없음"을 default no-op으로 뭉뚱그리면
// 호출부가 둘을 구분할 수 없다.
class ProviderCredentialLifecycleRegistryTest {

    @Test
    void findReturnsRegisteredLifecycleForProvider() {
        ProviderCredentialLifecycle lifecycle = mock(ProviderCredentialLifecycle.class);
        when(lifecycle.provider()).thenReturn(IntegrationProvider.JIRA);
        ProviderCredentialLifecycleRegistry registry =
                new ProviderCredentialLifecycleRegistry(List.of(lifecycle));

        assertThat(registry.find(IntegrationProvider.JIRA)).contains(lifecycle);
    }

    @Test
    void findReturnsEmptyForUnregisteredProvider() {
        ProviderCredentialLifecycleRegistry registry = new ProviderCredentialLifecycleRegistry(List.of());

        assertThat(registry.find(IntegrationProvider.GITHUB)).isEmpty();
    }
}
