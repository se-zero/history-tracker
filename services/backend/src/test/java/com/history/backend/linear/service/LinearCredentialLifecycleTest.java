package com.history.backend.linear.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.LinearCredential;
import com.history.backend.integration.service.LinearCredentialCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LinearCredentialLifecycle: Linear 연동 해제 시 권한 폐기")
class LinearCredentialLifecycleTest {

    private final LinearOAuthClient linearOAuthClient = mock(LinearOAuthClient.class);
    private final LinearCredentialCodec linearCredentialCodec = mock(LinearCredentialCodec.class);
    private final LinearCredentialLifecycle lifecycle =
            new LinearCredentialLifecycle(linearOAuthClient, linearCredentialCodec);

    @Test
    void providerIsLinear() {
        assertThat(lifecycle.provider()).isEqualTo(IntegrationProvider.LINEAR);
    }

    @Test
    @DisplayName("해제 시 자격증명을 복호화해 refresh token을 폐기한다 (파생 access token도 함께 무효화)")
    void revokeDecryptsCredentialAndRevokesRefreshToken() {
        byte[] encryptedCredential = {9, 8, 7};
        when(linearCredentialCodec.decrypt(encryptedCredential))
                .thenReturn(new LinearCredential("access-token", "refresh-token", Instant.parse("2026-08-01T00:00:00Z")));

        // Linear는 externalRef를 쓰지 않는다(Discord와 달리 해제 시 별도로 나가야 할 봇이 없다) — 빈 맵으로 충분하다.
        lifecycle.revoke(encryptedCredential, Map.of());

        verify(linearOAuthClient).revoke("refresh-token");
    }
}
