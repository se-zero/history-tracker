package com.history.backend.integration.service;

import java.util.UUID;

import com.history.backend.common.error.ConflictException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.project.service.ProjectService;
import com.history.backend.slack.SlackProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

// OAuth 동의 URL 조립과 콜백 처리를 오케스트레이션한다 (컨트롤러에 로직을 두지 않기 위한 얇은 계층).
// 콜백 요청에는 JWT가 없으므로, state 서명이 신원·프로젝트 소유권을 증명하는 유일한 수단이다.
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationOAuthService {

    private static final String SLACK_PROVIDER = IntegrationProvider.SLACK.value();

    private final ProjectService projectService;
    private final OAuthStateService oauthStateService;
    private final SlackProperties slackProperties;
    private final IntegrationService integrationService;

    // 소유권 확인 후 state를 발급해 Slack 동의 화면 URL을 조립한다
    public String buildSlackAuthorizeUrl(UUID userId, UUID projectId) {
        projectService.getProject(userId, projectId);
        String state = oauthStateService.issue(projectId, userId, SLACK_PROVIDER);

        return UriComponentsBuilder.fromUriString(slackProperties.authorizeUrl())
                .queryParam("client_id", slackProperties.clientId())
                .queryParam("user_scope", slackProperties.userScopes())
                .queryParam("redirect_uri", slackProperties.redirectUri())
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    // state를 먼저 검증해 projectId를 복원한 뒤, 동의 거부·연동 실패를 프론트 표시용 에러 코드로 변환한다.
    // state 자체가 위조·만료된 경우에만 projectId 없이 반환한다.
    public OAuthCallbackOutcome completeSlackCallback(String code, String state, String error) {
        OAuthStateClaims claims;
        try {
            claims = oauthStateService.verify(state, SLACK_PROVIDER);
        } catch (OAuthStateException exception) {
            // projectId를 알 수 없어 사용자에게 배너로 알릴 수 없는 경로 — 로그가 유일한 관측 수단
            log.warn("Slack OAuth callback rejected invalid state. reason={}", exception.getMessage());
            return new OAuthCallbackOutcome(null, SLACK_PROVIDER, "invalid_state");
        }

        if (error != null && !error.isBlank()) {
            // Slack이 보내는 provider error는 access_denied 외에도 다양하다(예: invalid_scope).
            // "취소했어요" 문구는 사용자가 실제로 거부한 경우에만 정확하므로 access_denied만 구분한다.
            if ("access_denied".equals(error)) {
                return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, "access_denied");
            }
            log.warn("Slack OAuth callback returned provider error. projectId={}, error={}", claims.projectId(), error);
            return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, "connect_failed");
        }

        try {
            integrationService.connectSlackWorkspace(claims.userId(), claims.projectId(), code);
            return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, null);
        } catch (ConflictException exception) {
            return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, "already_connected");
        } catch (RuntimeException exception) {
            log.warn("Slack OAuth callback failed. projectId={}, error={}", claims.projectId(), exception.getMessage());
            return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, "connect_failed");
        }
    }
}
