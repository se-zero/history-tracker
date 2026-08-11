package com.history.backend.asana.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AsanaTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AsanaAccessTokenRefresher: Asana access token 갱신 SPI")
class AsanaAccessTokenRefresherTest {

    private final AsanaTokenService asanaTokenService = mock(AsanaTokenService.class);
    private final AsanaAccessTokenRefresher refresher = new AsanaAccessTokenRefresher(asanaTokenService);

    @Test
    void providerIsAsana() {
        assertThat(refresher.provider()).isEqualTo(IntegrationProvider.ASANA);
    }

    @Test
    @DisplayName("ensureFreshAccessToken은 AsanaTokenService에 위임한다")
    void ensureFreshAccessTokenDelegatesToTokenService() {
        UUID projectId = UUID.randomUUID();

        refresher.ensureFreshAccessToken(projectId);

        verify(asanaTokenService).ensureAccessToken(projectId);
    }
}
