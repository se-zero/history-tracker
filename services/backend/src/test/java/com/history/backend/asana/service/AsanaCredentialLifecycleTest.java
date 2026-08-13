package com.history.backend.asana.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AsanaCredential;
import com.history.backend.integration.service.AsanaCredentialCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// LinearCredentialLifecycle과 동일한 패턴 — 복호화한 refresh token을 그대로 폐기에 넘긴다.
// Linear·Jira 모두 refresh token 부재를 별도로 방어하지 않으므로(항상 존재를 전제) 동일하게 따른다.
@DisplayName("AsanaCredentialLifecycle: Asana 연동 해제 시 권한 폐기")
class AsanaCredentialLifecycleTest {

    private final AsanaOAuthClient asanaOAuthClient = mock(AsanaOAuthClient.class);
    private final AsanaCredentialCodec asanaCredentialCodec = mock(AsanaCredentialCodec.class);
    private final AsanaCredentialLifecycle lifecycle =
            new AsanaCredentialLifecycle(asanaOAuthClient, asanaCredentialCodec);

    @Test
    void providerIsAsana() {
        assertThat(lifecycle.provider()).isEqualTo(IntegrationProvider.ASANA);
    }

    @Test
    @DisplayName("해제 시 자격증명을 복호화해 refresh token을 폐기한다 (access token이 아니라 refresh token을 보내야 한다)")
    void revokeDecryptsCredentialAndRevokesRefreshToken() {
        byte[] encryptedCredential = {9, 8, 7};
        when(asanaCredentialCodec.decrypt(encryptedCredential))
                .thenReturn(new AsanaCredential("access-token", "refresh-token", Instant.parse("2026-08-01T00:00:00Z")));

        lifecycle.revoke(encryptedCredential);

        verify(asanaOAuthClient).revoke("refresh-token");
    }
}
