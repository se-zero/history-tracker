package com.history.backend.linear.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.LinearCredential;
import com.history.backend.integration.service.LinearCredentialCodec;
import com.history.backend.integration.service.OAuthConnection;
import com.history.backend.linear.LinearProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("LinearOAuthConnectFlow: Linear 동의 code 교환")
class LinearOAuthConnectFlowTest {

    private final LinearProperties linearProperties = new LinearProperties(
            "test-linear-client-id",
            "test-linear-client-secret",
            "https://linear.test/callback",
            "https://api.linear.app/oauth/token",
            "https://api.linear.app/oauth/revoke",
            Duration.ofMinutes(30)
    );

    private final LinearOAuthClient linearOAuthClient = mock(LinearOAuthClient.class);
    private final LinearCredentialCodec linearCredentialCodec = mock(LinearCredentialCodec.class);
    private final LinearOAuthConnectFlow flow =
            new LinearOAuthConnectFlow(linearProperties, linearOAuthClient, linearCredentialCodec);

    @Test
    void providerIsLinear() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.LINEAR);
    }

    @Test
    @DisplayName("동의 URL은 client_id·redirect_uri·scope(read)·state를 담는다")
    void buildAuthorizeUrlAssemblesLinearParameters() {
        assertThat(flow.buildAuthorizeUrl("signed-state")).isEqualTo(
                "https://linear.app/oauth/authorize"
                        + "?client_id=test-linear-client-id"
                        + "&redirect_uri=https://linear.test/callback"
                        + "&response_type=code"
                        + "&scope=read"
                        + "&state=signed-state"
        );
    }

    @Test
    @DisplayName("code 교환 결과는 갱신용 refresh token·만료 시각을 담고, 수집 대상(team)은 미정(pending)이다")
    void exchangeCodeReturnsRenewableCredentialPendingSelection() {
        when(linearOAuthClient.exchangeCode("auth-code"))
                .thenReturn(new LinearOAuthClient.LinearTokens("linear-access-token", "linear-refresh-token", 86400L));
        ArgumentCaptor<LinearCredential> credentialCaptor = ArgumentCaptor.forClass(LinearCredential.class);
        when(linearCredentialCodec.serialize(credentialCaptor.capture())).thenReturn("serialized-credential");

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential()).isEqualTo("serialized-credential");
        // 수집 대상(team)은 동의 시점에 알 수 없다 — 선택 단계를 거쳐 확정된다
        assertThat(connection.externalRef()).isEmpty();
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("linear-access-token");
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("linear-refresh-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isAfter(Instant.now());
        // 암호화는 저장 정책(IntegrationService)의 몫이다 — flow는 직렬화까지만 한다
        verify(linearCredentialCodec, never()).encrypt(any(LinearCredential.class));
    }
}
