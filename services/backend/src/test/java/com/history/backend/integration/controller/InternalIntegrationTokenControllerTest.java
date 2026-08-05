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
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import com.history.backend.integration.service.ProviderCredentialLifecycleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("InternalIntegrationTokenController: 내부 토큰 갱신 API")
class InternalIntegrationTokenControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Test
    @DisplayName("Jira 토큰 확보 요청 → 204 No Content 반환")
    void ensureAccessTokenReturnsNoContent() {
        ProviderCredentialLifecycle jira = lifecycle(IntegrationProvider.JIRA);
        InternalIntegrationTokenController controller = controller(jira);

        ResponseEntity<Void> response = controller.ensureAccessToken(PROJECT_ID, "jira");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(jira).ensureFreshAccessToken(PROJECT_ID);
    }

    @Test
    @DisplayName("갱신 수단이 없는 provider → 404 (조용한 204로 넘기지 않는다)")
    void ensureAccessTokenRejectsProviderWithoutRefresh() {
        InternalIntegrationTokenController controller = controller(lifecycle(IntegrationProvider.JIRA));

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

    private ProviderCredentialLifecycle lifecycle(IntegrationProvider provider) {
        ProviderCredentialLifecycle lifecycle = mock(ProviderCredentialLifecycle.class);
        when(lifecycle.provider()).thenReturn(provider);
        return lifecycle;
    }

    private InternalIntegrationTokenController controller(ProviderCredentialLifecycle... lifecycles) {
        return new InternalIntegrationTokenController(
                new ProviderCredentialLifecycleRegistry(List.of(lifecycles)));
    }
}
