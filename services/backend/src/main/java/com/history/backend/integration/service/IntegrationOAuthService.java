package com.history.backend.integration.service;

import java.util.UUID;

import com.history.backend.common.error.ConflictException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.jira.AtlassianProperties;
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
    private static final String JIRA_PROVIDER = IntegrationProvider.JIRA.value();

    private final ProjectService projectService;
    private final OAuthStateService oauthStateService;
    private final SlackProperties slackProperties;
    private final AtlassianProperties atlassianProperties;
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
            return new OAuthCallbackOutcome(null, SLACK_PROVIDER, "invalid_state", false);
        }

        if (error != null && !error.isBlank()) {
            // Slack이 보내는 provider error는 access_denied 외에도 다양하다(예: invalid_scope).
            // "취소했어요" 문구는 사용자가 실제로 거부한 경우에만 정확하므로 access_denied만 구분한다.
            if ("access_denied".equals(error)) {
                return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, "access_denied", false);
            }
            log.warn("Slack OAuth callback returned provider error. projectId={}, error={}", claims.projectId(), error);
            return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, "connect_failed", false);
        }

        try {
            integrationService.connectSlackWorkspace(claims.userId(), claims.projectId(), code);
            return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, null, false);
        } catch (ConflictException exception) {
            return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, "already_connected", false);
        } catch (RuntimeException exception) {
            log.warn("Slack OAuth callback failed. projectId={}, error={}", claims.projectId(), exception.getMessage());
            return new OAuthCallbackOutcome(claims.projectId(), SLACK_PROVIDER, "connect_failed", false);
        }
    }

    // 소유권 확인 후 state를 발급해 Atlassian 동의 화면 URL을 조립한다.
    // Atlassian은 Slack보다 파라미터가 많다 — audience 고정값, response_type/prompt 지정이 필요하다.
    public String buildJiraAuthorizeUrl(UUID userId, UUID projectId) {
        projectService.getProject(userId, projectId);
        String state = oauthStateService.issue(projectId, userId, JIRA_PROVIDER);

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

    // state 검증 후 Jira 연동을 pending 상태로 생성/재시도한다. 에러 코드 매핑은 Slack과 동일하다.
    public OAuthCallbackOutcome completeJiraCallback(String code, String state, String error) {
        OAuthStateClaims claims;
        try {
            claims = oauthStateService.verify(state, JIRA_PROVIDER);
        } catch (OAuthStateException exception) {
            log.warn("Jira OAuth callback rejected invalid state. reason={}", exception.getMessage());
            return new OAuthCallbackOutcome(null, JIRA_PROVIDER, "invalid_state", false);
        }

        if (error != null && !error.isBlank()) {
            if ("access_denied".equals(error)) {
                return new OAuthCallbackOutcome(claims.projectId(), JIRA_PROVIDER, "access_denied", false);
            }
            log.warn("Jira OAuth callback returned provider error. projectId={}, error={}", claims.projectId(), error);
            return new OAuthCallbackOutcome(claims.projectId(), JIRA_PROVIDER, "connect_failed", false);
        }

        try {
            Integration integration = integrationService.connectJiraSite(claims.userId(), claims.projectId(), code);
            // 자동 복원으로 이미 확정된 경우에만 true — 사이트·프로젝트 선택이 남아 있으면 false
            return new OAuthCallbackOutcome(claims.projectId(), JIRA_PROVIDER, null, !integration.isJiraPendingProject());
        } catch (ConflictException exception) {
            return new OAuthCallbackOutcome(claims.projectId(), JIRA_PROVIDER, "already_connected", false);
        } catch (RuntimeException exception) {
            log.warn("Jira OAuth callback failed. projectId={}, error={}", claims.projectId(), exception.getMessage());
            return new OAuthCallbackOutcome(claims.projectId(), JIRA_PROVIDER, "connect_failed", false);
        }
    }
}
