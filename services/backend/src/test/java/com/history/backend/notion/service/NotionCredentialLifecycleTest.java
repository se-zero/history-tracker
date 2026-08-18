package com.history.backend.notion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.NotionCredential;
import com.history.backend.integration.service.NotionCredentialCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NotionCredentialLifecycle: 연동 해제 시 access token 폐기")
class NotionCredentialLifecycleTest {

    private final NotionClient notionClient = mock(NotionClient.class);
    private final NotionCredentialCodec credentialCodec = mock(NotionCredentialCodec.class);
    private final NotionCredentialLifecycle lifecycle =
            new NotionCredentialLifecycle(notionClient, credentialCodec);

    @Test
    void providerIsNotion() {
        assertThat(lifecycle.provider()).isEqualTo(IntegrationProvider.NOTION);
    }

    @Test
    @DisplayName("externalRef 없이 access_token만으로 폐기한다 — 남길 봇이 없어 Discord처럼 별도 처리가 필요 없다")
    void revokeCallsAccessTokenRevokeOnly() {
        byte[] encrypted = {1, 2, 3};
        when(credentialCodec.decrypt(encrypted)).thenReturn(new NotionCredential("access-token", "refresh-token"));

        lifecycle.revoke(encrypted, Map.of());

        verify(notionClient).revoke("access-token");
    }
}
