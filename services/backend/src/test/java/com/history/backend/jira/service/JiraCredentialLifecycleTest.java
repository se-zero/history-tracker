package com.history.backend.jira.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.JiraCredential;
import com.history.backend.integration.service.JiraCredentialCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JiraCredentialLifecycle: Jira 연동 해제 시 권한 폐기")
class JiraCredentialLifecycleTest {

    private final JiraOAuthClient jiraOAuthClient = mock(JiraOAuthClient.class);
    private final JiraCredentialCodec jiraCredentialCodec = mock(JiraCredentialCodec.class);
    private final JiraCredentialLifecycle lifecycle =
            new JiraCredentialLifecycle(jiraOAuthClient, jiraCredentialCodec);

    @Test
    void providerIsJira() {
        assertThat(lifecycle.provider()).isEqualTo(IntegrationProvider.JIRA);
    }

    @Test
    @DisplayName("client가 성공(true)을 반환하면 그대로 전달한다")
    void revokeReturnsTrueWhenClientSucceeds() {
        byte[] encryptedCredential = {9, 8, 7};
        when(jiraCredentialCodec.decrypt(encryptedCredential))
                .thenReturn(new JiraCredential("access-token", "refresh-token", Instant.parse("2026-08-01T00:00:00Z")));
        when(jiraOAuthClient.revoke("refresh-token")).thenReturn(true);

        boolean result = lifecycle.revoke(encryptedCredential, Map.of());

        assertThat(result).isTrue();
        verify(jiraOAuthClient).revoke("refresh-token");
    }

    @Test
    @DisplayName("client가 실패(false)를 반환하면 그대로 전달한다")
    void revokeReturnsFalseWhenClientFails() {
        byte[] encryptedCredential = {9, 8, 7};
        when(jiraCredentialCodec.decrypt(encryptedCredential))
                .thenReturn(new JiraCredential("access-token", "refresh-token", Instant.parse("2026-08-01T00:00:00Z")));
        when(jiraOAuthClient.revoke("refresh-token")).thenReturn(false);

        boolean result = lifecycle.revoke(encryptedCredential, Map.of());

        assertThat(result).isFalse();
        verify(jiraOAuthClient).revoke("refresh-token");
    }
}
