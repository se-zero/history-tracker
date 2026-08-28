package com.history.backend.googlechat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.GoogleChatCredential;
import com.history.backend.integration.service.GoogleChatCredentialCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GoogleChatCredentialLifecycle: Google Chat 연동 해제 시 권한 폐기")
class GoogleChatCredentialLifecycleTest {

    private final GoogleChatClient client = mock(GoogleChatClient.class);
    private final GoogleChatCredentialCodec credentialCodec = mock(GoogleChatCredentialCodec.class);
    private final GoogleChatCredentialLifecycle lifecycle =
            new GoogleChatCredentialLifecycle(client, credentialCodec);

    @Test
    void providerIsGoogleChat() {
        assertThat(lifecycle.provider()).isEqualTo(IntegrationProvider.GOOGLE_CHAT);
    }

    @Test
    @DisplayName("client가 성공(true)을 반환하면 그대로 전달한다")
    void revokeReturnsTrueWhenClientSucceeds() {
        byte[] encryptedCredential = {9, 8, 7};
        when(credentialCodec.decrypt(encryptedCredential))
                .thenReturn(new GoogleChatCredential("access-token", "refresh-token", Instant.parse("2026-08-01T00:00:00Z")));
        when(client.revoke("refresh-token")).thenReturn(true);

        boolean result = lifecycle.revoke(encryptedCredential, Map.of());

        assertThat(result).isTrue();
        verify(client).revoke("refresh-token");
    }

    @Test
    @DisplayName("client가 실패(false)를 반환하면 그대로 전달한다")
    void revokeReturnsFalseWhenClientFails() {
        byte[] encryptedCredential = {9, 8, 7};
        when(credentialCodec.decrypt(encryptedCredential))
                .thenReturn(new GoogleChatCredential("access-token", "refresh-token", Instant.parse("2026-08-01T00:00:00Z")));
        when(client.revoke("refresh-token")).thenReturn(false);

        boolean result = lifecycle.revoke(encryptedCredential, Map.of());

        assertThat(result).isFalse();
        verify(client).revoke("refresh-token");
    }
}
