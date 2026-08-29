package com.history.backend.jira.service;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.JiraCredentialCodec;
import com.history.backend.integration.service.ProviderCredentialLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JiraCredentialLifecycle implements ProviderCredentialLifecycle {

    private final JiraOAuthClient jiraOAuthClient;
    private final JiraCredentialCodec jiraCredentialCodec;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.JIRA;
    }

    // refresh token을 폐기하면 파생된 access token도 함께 무효화된다 — externalRef는 필요 없다
    @Override
    public boolean revoke(byte[] encryptedCredential, Map<String, Object> externalRef) {
        return jiraOAuthClient.revoke(jiraCredentialCodec.decrypt(encryptedCredential).refreshToken());
    }
}
