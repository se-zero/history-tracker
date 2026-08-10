package com.history.backend.googlechat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import com.history.backend.googlechat.GoogleChatProperties;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.GoogleChatCredential;
import com.history.backend.integration.service.GoogleChatCredentialCodec;
import com.history.backend.integration.service.OAuthConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("GoogleChatOAuthConnectFlow: 동의 URL 조립·code 교환")
class GoogleChatOAuthConnectFlowTest {

    private final GoogleChatProperties properties = new GoogleChatProperties(
            "test-client-id",
            "test-client-secret",
            "https://googlechat.test/callback",
            "https://www.googleapis.com/auth/chat.spaces.readonly https://www.googleapis.com/auth/chat.messages.readonly",
            "https://googlechat.test/o/oauth2/v2/auth",
            "https://googlechat.test/token",
            "https://googlechat.test/revoke",
            "https://googlechat.test/v1",
            Duration.ofMinutes(5)
    );

    private final GoogleChatClient client = mock(GoogleChatClient.class);
    private final GoogleChatCredentialCodec credentialCodec = mock(GoogleChatCredentialCodec.class);
    private final GoogleChatOAuthConnectFlow flow =
            new GoogleChatOAuthConnectFlow(properties, client, credentialCodec);

    @Test
    void providerIsGoogleChat() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.GOOGLE_CHAT);
    }

    @Test
    @DisplayName("동의 URL은 access_type=offline·prompt=consent를 반드시 포함한다 — 없으면 refresh token이 안 온다")
    void buildAuthorizeUrlIncludesOfflineAccessAndConsentPrompt() {
        assertThat(flow.buildAuthorizeUrl("signed-state")).isEqualTo(
                "https://googlechat.test/o/oauth2/v2/auth"
                        + "?client_id=test-client-id"
                        + "&response_type=code"
                        + "&redirect_uri=https://googlechat.test/callback"
                        + "&scope=https://www.googleapis.com/auth/chat.spaces.readonly%20"
                        + "https://www.googleapis.com/auth/chat.messages.readonly"
                        + "&access_type=offline"
                        + "&prompt=consent"
                        + "&state=signed-state"
        );
    }

    @Test
    @DisplayName("code 교환 결과는 갱신용 refresh token·만료 시각을 담고, 수집 대상(스페이스)은 미정(pending)이다")
    void exchangeCodeReturnsRenewableCredentialPendingSelection() {
        when(client.exchangeCode("auth-code"))
                .thenReturn(new GoogleChatClient.GoogleChatTokens("gc-access-token", "gc-refresh-token", 3599L));
        ArgumentCaptor<GoogleChatCredential> credentialCaptor = ArgumentCaptor.forClass(GoogleChatCredential.class);
        when(credentialCodec.serialize(credentialCaptor.capture())).thenReturn("serialized-credential");

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential()).isEqualTo("serialized-credential");
        // 스페이스는 동의 시점에 알 수 없다 — GoogleChatSelectionFlow가 선언한 단계를 거쳐 확정된다
        assertThat(connection.externalRef()).isEmpty();
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("gc-access-token");
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("gc-refresh-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isAfter(Instant.now());
        // 암호화는 저장 정책(IntegrationService)의 몫이다 — flow는 직렬화까지만 한다
        verify(credentialCodec, never()).encrypt(any(GoogleChatCredential.class));
    }
}
