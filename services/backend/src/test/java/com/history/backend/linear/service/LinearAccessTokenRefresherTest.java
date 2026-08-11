package com.history.backend.linear.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.LinearTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LinearAccessTokenRefresher: Linear access token 갱신 SPI")
class LinearAccessTokenRefresherTest {

    private final LinearTokenService linearTokenService = mock(LinearTokenService.class);
    private final LinearAccessTokenRefresher refresher = new LinearAccessTokenRefresher(linearTokenService);

    @Test
    void providerIsLinear() {
        assertThat(refresher.provider()).isEqualTo(IntegrationProvider.LINEAR);
    }

    @Test
    @DisplayName("ensureFreshAccessToken은 LinearTokenService에 위임한다 — 회전 토큰이라 갱신 주체는 하나여야 한다")
    void ensureFreshAccessTokenDelegatesToTokenService() {
        UUID projectId = UUID.randomUUID();

        refresher.ensureFreshAccessToken(projectId);

        verify(linearTokenService).ensureAccessToken(projectId);
    }
}
