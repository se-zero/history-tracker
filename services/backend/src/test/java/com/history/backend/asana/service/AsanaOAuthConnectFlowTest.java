package com.history.backend.asana.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import com.history.backend.asana.AsanaProperties;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AsanaCredential;
import com.history.backend.integration.service.AsanaCredentialCodec;
import com.history.backend.integration.service.OAuthConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("AsanaOAuthConnectFlow: Asana 동의 code 교환")
class AsanaOAuthConnectFlowTest {

    private final AsanaProperties asanaProperties = new AsanaProperties(
            "test-asana-client-id",
            "test-asana-client-secret",
            "https://asana.test/callback",
            "https://app.asana.com/-/oauth_token",
            "https://app.asana.com/-/oauth_revoke",
            Duration.ofMinutes(30)
    );

    private final AsanaOAuthClient asanaOAuthClient = mock(AsanaOAuthClient.class);
    private final AsanaCredentialCodec asanaCredentialCodec = mock(AsanaCredentialCodec.class);
    private final AsanaOAuthConnectFlow flow =
            new AsanaOAuthConnectFlow(asanaProperties, asanaOAuthClient, asanaCredentialCodec);

    @Test
    void providerIsAsana() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.ASANA);
    }

    @Test
    @DisplayName("동의 URL은 client_id·redirect_uri·scope(workspaces/projects/tasks/users:read)·state를 담는다")
    void buildAuthorizeUrlAssemblesAsanaParameters() {
        assertThat(flow.buildAuthorizeUrl("signed-state")).isEqualTo(
                "https://app.asana.com/-/oauth_authorize"
                        + "?client_id=test-asana-client-id"
                        + "&redirect_uri=https://asana.test/callback"
                        + "&response_type=code"
                        + "&scope=workspaces:read%20projects:read%20tasks:read%20users:read"
                        + "&state=signed-state"
        );
    }

    @Test
    @DisplayName("code 교환 결과는 갱신용 refresh token·만료 시각을 담고, 수집 대상(workspace/project)은 미정(pending)이다")
    void exchangeCodeReturnsRenewableCredentialPendingSelection() {
        when(asanaOAuthClient.exchangeCode("auth-code"))
                .thenReturn(new AsanaOAuthClient.AsanaTokens("asana-access-token", "asana-refresh-token", 3600L));
        ArgumentCaptor<AsanaCredential> credentialCaptor = ArgumentCaptor.forClass(AsanaCredential.class);
        when(asanaCredentialCodec.serialize(credentialCaptor.capture())).thenReturn("serialized-credential");

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential()).isEqualTo("serialized-credential");
        // 수집 대상(workspace/project)은 동의 시점에 알 수 없다 — 선택 단계를 거쳐 확정된다
        assertThat(connection.externalRef()).isEmpty();
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("asana-access-token");
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("asana-refresh-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isAfter(Instant.now());
        // 암호화는 저장 정책(IntegrationService)의 몫이다 — flow는 직렬화까지만 한다
        verify(asanaCredentialCodec, never()).encrypt(any(AsanaCredential.class));
    }
}
