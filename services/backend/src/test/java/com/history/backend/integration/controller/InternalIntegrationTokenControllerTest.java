package com.history.backend.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.NotFoundException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AccessTokenRefresher;
import com.history.backend.integration.service.AccessTokenRefresherRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("InternalIntegrationTokenController: 내부 토큰 갱신 API")
class InternalIntegrationTokenControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    @DisplayName("Jira 토큰 확보 요청 → 204 No Content 반환")
    void ensureAccessTokenReturnsNoContent() {
        AccessTokenRefresher jira = refresher(IntegrationProvider.JIRA);
        InternalIntegrationTokenController controller = controller(jira);

        ResponseEntity<Void> response = controller.ensureAccessToken(PROJECT_ID, "jira");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(jira).ensureFreshAccessToken(PROJECT_ID);
    }

    @Test
    @DisplayName("폐기만 있고 갱신은 없는 provider(Slack) → 404, 조용한 204로 넘기지 않는다")
    void ensureAccessTokenRejectsProviderThatOnlySupportsRevoke() {
        // 판정 기준이 "자격증명 빈이 있는가"였을 때 Slack이 통과해 조용한 204를 받던 회귀를 고정한다 —
        // 호출부가 갱신됐다고 오인한 채 만료된 토큰으로 수집하게 된다.
        InternalIntegrationTokenController controller = controller(refresher(IntegrationProvider.JIRA));

        assertThatThrownBy(() -> controller.ensureAccessToken(PROJECT_ID, "slack"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("does not support access token refresh");
    }

    @Test
    @DisplayName("자격증명이 없는 provider(GitHub) → 404")
    void ensureAccessTokenRejectsProviderWithoutCredential() {
        InternalIntegrationTokenController controller = controller(refresher(IntegrationProvider.JIRA));

        assertThatThrownBy(() -> controller.ensureAccessToken(PROJECT_ID, "github"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("알 수 없는 provider → 404")
    void ensureAccessTokenRejectsUnknownProvider() {
        InternalIntegrationTokenController controller = controller();

        assertThatThrownBy(() -> controller.ensureAccessToken(PROJECT_ID, "linear"))
                .isInstanceOf(NotFoundException.class);
    }

    private AccessTokenRefresher refresher(IntegrationProvider provider) {
        AccessTokenRefresher refresher = mock(AccessTokenRefresher.class);
        when(refresher.provider()).thenReturn(provider);
        return refresher;
    }

    private InternalIntegrationTokenController controller(AccessTokenRefresher... refreshers) {
        return new InternalIntegrationTokenController(
                new AccessTokenRefresherRegistry(List.of(refreshers)));
    }
}
