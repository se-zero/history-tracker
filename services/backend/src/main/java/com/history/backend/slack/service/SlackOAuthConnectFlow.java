package com.history.backend.slack.service;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.integration.service.OAuthConnectFlow;
import com.history.backend.slack.SlackProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class SlackOAuthConnectFlow implements OAuthConnectFlow {

    private final SlackProperties slackProperties;
    private final IntegrationService integrationService;

    @Override
    public IntegrationProvider provider() {
        return IntegrationProvider.SLACK;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(slackProperties.authorizeUrl())
                .queryParam("client_id", slackProperties.clientId())
                .queryParam("user_scope", slackProperties.userScopes())
                .queryParam("redirect_uri", slackProperties.redirectUri())
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    @Override
    public boolean connect(UUID userId, UUID projectId, String code) {
        integrationService.connectSlackWorkspace(userId, projectId, code);
        // Slack은 동의 후 선택 단계가 없다 — 자동 복원 개념이 없으므로 "복원 완료" 배너 대상이 아니다.
        return false;
    }
}
