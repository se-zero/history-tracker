package com.history.backend.jira.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.JiraCredentialCodec;
import com.history.backend.integration.service.JiraTokenService;
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JiraCredentialLifecycle implements ProviderCredentialLifecycle {

    private final JiraOAuthClient jiraOAuthClient;
    private final JiraCredentialCodec jiraCredentialCodec;
    private final JiraTokenService jiraTokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.JIRA;
    }

    // refresh token을 폐기하면 파생된 access token도 함께 무효화된다
    @Override
    public void revoke(byte[] encryptedCredential) {
        jiraOAuthClient.revoke(jiraCredentialCodec.decrypt(encryptedCredential).refreshToken());
    }

    @Override
    public void ensureFreshAccessToken(UUID projectId) {
        jiraTokenService.ensureAccessToken(projectId);
    }
}
