package com.history.backend.clickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.history.backend.clickup.ClickUpProperties;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.ClickUpCredential;
import com.history.backend.integration.service.ClickUpCredentialCodec;
import com.history.backend.integration.service.OAuthConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("ClickUpOAuthConnectFlow: ClickUp 동의 code 교환")
class ClickUpOAuthConnectFlowTest {

    private final ClickUpProperties clickUpProperties = new ClickUpProperties(
            "test-clickup-client-id",
            "test-clickup-client-secret",
            "https://clickup.test/callback",
            "https://api.clickup.com/api/v2/oauth/token"
    );

    private final ClickUpOAuthClient clickUpOAuthClient = mock(ClickUpOAuthClient.class);
    private final ClickUpCredentialCodec clickUpCredentialCodec = mock(ClickUpCredentialCodec.class);
    private final ClickUpOAuthConnectFlow flow =
            new ClickUpOAuthConnectFlow(clickUpProperties, clickUpOAuthClient, clickUpCredentialCodec);

    @Test
    void providerIsClickUp() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.CLICKUP);
    }

    @Test
    @DisplayName("동의 URL은 client_id·redirect_uri·state만 담는다 (scope·response_type 개념이 없다)")
    void buildAuthorizeUrlAssemblesClickUpParameters() {
        assertThat(flow.buildAuthorizeUrl("signed-state")).isEqualTo(
                "https://app.clickup.com/api"
                        + "?client_id=test-clickup-client-id"
                        + "&redirect_uri=https://clickup.test/callback"
                        + "&state=signed-state"
        );
    }

    @Test
    @DisplayName("code 교환 결과는 access token만 담고, 수집 대상(workspace/space/folder/list)은 미정(pending)이다")
    void exchangeCodeReturnsCredentialPendingSelection() {
        when(clickUpOAuthClient.exchangeCode("auth-code"))
                .thenReturn(new ClickUpOAuthClient.ClickUpTokens("clickup-access-token"));
        ArgumentCaptor<ClickUpCredential> credentialCaptor = ArgumentCaptor.forClass(ClickUpCredential.class);
        when(clickUpCredentialCodec.serialize(credentialCaptor.capture())).thenReturn("serialized-credential");

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential()).isEqualTo("serialized-credential");
        // 수집 대상(workspace/space/folder/list)은 동의 시점에 알 수 없다 — 선택 단계를 거쳐 확정된다
        assertThat(connection.externalRef()).isEmpty();
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("clickup-access-token");
    }
}
