package com.history.backend.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @DisplayName("폐기만 있고 갱신은 없는 provider(Slack) → 501, 조용한 204로 넘기지 않는다")
    void ensureAccessTokenReportsNotImplementedForProviderThatOnlySupportsRevoke() {
        // 판정 기준이 "자격증명 빈이 있는가"였을 때 Slack이 통과해 조용한 204를 받던 회귀를 고정한다 —
        // 호출부가 갱신됐다고 오인한 채 만료된 토큰으로 수집하게 된다.
        AccessTokenRefresher jira = refresher(IntegrationProvider.JIRA);
        InternalIntegrationTokenController controller = controller(jira);

        ResponseEntity<Void> response = controller.ensureAccessToken(PROJECT_ID, "slack");

        assertThat(response.getStatusCode().value()).isEqualTo(501);
        verify(jira, never()).ensureFreshAccessToken(PROJECT_ID);
    }

    @Test
    @DisplayName("자격증명이 없는 provider(GitHub) → 501")
    void ensureAccessTokenReportsNotImplementedForProviderWithoutCredential() {
        InternalIntegrationTokenController controller = controller(refresher(IntegrationProvider.JIRA));

        assertThat(controller.ensureAccessToken(PROJECT_ID, "github").getStatusCode().value())
                .isEqualTo(501);
    }

    @Test
    @DisplayName("갱신기는 있는데 연동 행이 없으면 404 — 501(갱신 수단 없음)과 구분된다")
    void ensureAccessTokenPropagatesNotFoundWhenIntegrationRowIsMissing() {
        // 해제 직후 레이스. 호출부가 이걸 "갱신 불필요"로 읽으면 폐기된 토큰으로 수집을 진행한다.
        AccessTokenRefresher jira = refresher(IntegrationProvider.JIRA);
        doThrow(new NotFoundException("Jira integration not found."))
                .when(jira).ensureFreshAccessToken(PROJECT_ID);
        InternalIntegrationTokenController controller = controller(jira);

        assertThatThrownBy(() -> controller.ensureAccessToken(PROJECT_ID, "jira"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("integration not found");
    }

    @Test
    @DisplayName("알 수 없는 provider → 404")
    void ensureAccessTokenRejectsUnknownProvider() {
        InternalIntegrationTokenController controller = controller();

        // IntegrationProvider에 실재하지 않는 값을 쓴다 — 있는 값을 쓰면 parseProvider를 통과해
        // 버려 이 테스트가 검증하려는 "알 수 없는 provider" 경로 자체가 사라진다.
        assertThatThrownBy(() -> controller.ensureAccessToken(PROJECT_ID, "not-a-real-provider"))
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
