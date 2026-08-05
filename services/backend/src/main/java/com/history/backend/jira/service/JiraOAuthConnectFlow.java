package com.history.backend.jira.service;

import java.util.UUID;

import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.jira.AtlassianProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class JiraOAuthConnectFlow implements OAuthConnectFlow {

    private final AtlassianProperties atlassianProperties;
    private final IntegrationService integrationService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.JIRA;
    }

    // Atlassian은 Slack보다 파라미터가 많다 — audience 고정값, response_type/prompt 지정이 필요하다.
    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(atlassianProperties.authorizeUrl())
                .queryParam("audience", "api.atlassian.com")
                .queryParam("client_id", atlassianProperties.clientId())
                .queryParam("scope", atlassianProperties.scopes())
                .queryParam("redirect_uri", atlassianProperties.redirectUri())
                .queryParam("state", state)
                .queryParam("response_type", "code")
                .queryParam("prompt", "consent")
                .encode()
                .build()
                .toUriString();
    }

    @Override
    public boolean connect(UUID userId, UUID projectId, String code) {
        Integration integration = integrationService.connectJiraSite(userId, projectId, code);
        // 자동 복원으로 이미 확정된 경우에만 true — 사이트·프로젝트 선택이 남아 있으면 false
        return !integration.isPendingSelection();
    }
}
