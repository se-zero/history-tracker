package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.integration.domain.IntegrationProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SlackCredentialLifecycle: 연동 해제 시 user 토큰 폐기")
class SlackCredentialLifecycleTest {

    private final SlackClient slackClient = mock(SlackClient.class);
    private final CredentialCryptoService credentialCryptoService = mock(CredentialCryptoService.class);
    private final SlackCredentialLifecycle lifecycle =
            new SlackCredentialLifecycle(slackClient, credentialCryptoService);

    @Test
    void providerIsSlack() {
        assertThat(lifecycle.provider()).isEqualTo(IntegrationProvider.SLACK);
    }

    @Test
    @DisplayName("client가 성공(true)을 반환하면 그대로 전달한다")
    void revokeReturnsTrueWhenClientSucceeds() {
        byte[] encrypted = {1, 2, 3};
        when(credentialCryptoService.decrypt(encrypted)).thenReturn("xoxp-token");
        when(slackClient.revoke("xoxp-token")).thenReturn(true);

        boolean result = lifecycle.revoke(encrypted, Map.of());

        assertThat(result).isTrue();
        verify(slackClient).revoke("xoxp-token");
    }

    @Test
    @DisplayName("client가 실패(false)를 반환하면 그대로 전달한다")
    void revokeReturnsFalseWhenClientFails() {
        byte[] encrypted = {1, 2, 3};
        when(credentialCryptoService.decrypt(encrypted)).thenReturn("xoxp-token");
        when(slackClient.revoke("xoxp-token")).thenReturn(false);

        boolean result = lifecycle.revoke(encrypted, Map.of());

        assertThat(result).isFalse();
        verify(slackClient).revoke("xoxp-token");
    }
}
