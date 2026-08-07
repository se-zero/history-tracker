package com.history.backend.jira.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.AccessTokenRefresher;
import com.history.backend.integration.service.JiraTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Atlassian access token은 1시간 만료라 갱신이 필요하다.
// 회전하는 refresh token을 둘이 갱신하면 서로를 무효화하므로 갱신 주체는 JiraTokenService 하나다.
@Service
@RequiredArgsConstructor
public class JiraAccessTokenRefresher implements AccessTokenRefresher {

    private final JiraTokenService jiraTokenService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.JIRA;
    }

    @Override
    public void ensureFreshAccessToken(UUID projectId) {
        jiraTokenService.ensureAccessToken(projectId);
    }
}
